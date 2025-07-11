package cash.atto.node.election

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoUnit
import cash.atto.commons.AttoVote
import cash.atto.commons.toAttoVersion
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountUpdated
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.PublicKeyHeight
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVotePush
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

@Service
class ElectionVoter(
    private val thisNode: AttoNode,
    private val signer: AttoSigner,
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher,
    private val accountRepository: AccountRepository,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        val MIN_WEIGHT = AttoAmount.from(AttoUnit.ATTO, BigDecimal.valueOf(1_000_000).toString()) // 1M
        val finalVoteTimestamp = AttoVote.finalTimestamp.toJavaInstant()
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val mutex = Mutex()

    private val consensusMap = HashMap<PublicKeyHeight, Consensus>()

    private val pending = HashMap<PublicKeyHeight, Job>()

    private suspend fun consensed(
        transaction: Transaction,
        consensusTimestamp: Instant,
    ) {
        val publicKeyHeight = transaction.toPublicKeyHeight()

        val consensus = consensusMap[publicKeyHeight]

        val newConsensus = Consensus(transaction, consensusTimestamp)

        if (consensus != null && consensus.transaction == newConsensus.transaction) {
            if (consensus.consensusTimestamp < newConsensus.consensusTimestamp) {
                consensusMap[publicKeyHeight] = newConsensus
            }
            return
        }

        logger.trace { "Consensus changed from ${consensus?.hash} to ${transaction.hash}" }

        consensusMap[publicKeyHeight] = newConsensus

        if (consensus == null) {
            vote(transaction, Instant.now())
        } else {
            voteAsynchronously(transaction, consensusTimestamp)
        }
    }

    @EventListener
    suspend fun process(event: ElectionStarted) {
        val publicKeyHeight = event.transaction.toPublicKeyHeight()

        mutex.withLock {
            if (consensusMap[publicKeyHeight] != null) {
                return
            }

            consensed(event.transaction, event.timestamp)
        }
    }

    private suspend fun consensusReached(transaction: Transaction) {
        remove(transaction)
        vote(transaction, Instant.now())
    }

    @EventListener
    suspend fun process(event: ElectionConsensusChanged) {
        val transaction = event.transaction

        mutex.withLock {
            consensed(transaction, event.timestamp)
        }
    }

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        mutex.withLock {
            consensusReached(event.transaction)
        }
    }

    @EventListener
    suspend fun process(event: ElectionExpiring) {
        val publicKeyHeight = event.transaction.toPublicKeyHeight()
        mutex.withLock {
            val consensus = consensusMap[publicKeyHeight]
            if (consensus == null) {
                return
            }
            vote(consensus.transaction, Instant.now())
        }
    }

    @EventListener
    suspend fun process(event: ElectionExpired) =
        mutex.withLock {
            remove(event.transaction)
        }

    @EventListener
    suspend fun process(event: AccountUpdated) {
        if (event.source != TransactionSource.ELECTION) {
            return
        }

        mutex.withLock {
            vote(event.transaction, finalVoteTimestamp)
        }
    }

    @EventListener
    suspend fun process(event: TransactionRejected) {
        if (event.reason != TransactionRejectionReason.OLD_TRANSACTION) {
            return
        }

        val account = accountRepository.findById(event.transaction.publicKey)
        if (account?.lastTransactionHash != event.transaction.hash) {
            return
        }

        mutex.withLock {
            vote(event.transaction, finalVoteTimestamp)
        }
    }

    /**
     * During the election phase, multiple transactions at the same height can trigger a flurry of votes,
     * making the provisional consensus bounce back and forth.
     *
     * Pausing for 5 seconds lets the network accumulate all votes first, so we cast a new vote
     * against a more stable consensus instead of chasing transient shifts.
     */
    private suspend fun voteAsynchronously(
        transaction: Transaction,
        timestamp: Instant,
    ) {
        val publicKeyHeight = transaction.toPublicKeyHeight()
        pending.remove(publicKeyHeight)?.cancel()
        val job =
            scope.launch {
                delay(5.seconds)
                mutex.withLock {
                    vote(transaction, timestamp)
                    pending.remove(publicKeyHeight)
                }
            }
        pending[publicKeyHeight] = job
    }

    private suspend fun vote(
        transaction: Transaction,
        timestamp: Instant,
    ) {
        val weight = voteWeighter.get()
        if (!canVote(weight)) {
            logger.trace { "This node can't vote yet" }
            return
        }

        val attoVote =
            AttoVote(
                version = 0U.toAttoVersion(),
                algorithm = thisNode.algorithm,
                publicKey = thisNode.publicKey,
                blockAlgorithm = transaction.algorithm,
                blockHash = transaction.hash,
                timestamp = timestamp.toKotlinInstant(),
            )
        val attoSignedVote =
            AttoSignedVote(
                vote = attoVote,
                signature = signer.sign(attoVote),
            )

        val votePush =
            AttoVotePush(
                vote = attoSignedVote,
            )

        val strategy =
            if (attoVote.isFinal()) {
                BroadcastStrategy.EVERYONE
            } else {
                BroadcastStrategy.VOTERS
            }

        logger.debug { "Sending to $strategy $votePush" }

        messagePublisher.publish(BroadcastNetworkMessage(strategy, emptySet(), votePush))
        eventPublisher.publish(VoteValidated(transaction, Vote.from(weight, attoSignedVote)))
    }

    private fun canVote(weight: AttoAmount): Boolean = thisNode.isVoter() && weight >= MIN_WEIGHT

    private suspend fun remove(transaction: Transaction) {
        val publicKeyHeight = transaction.toPublicKeyHeight()
        consensusMap.remove(publicKeyHeight)
        pending.remove(publicKeyHeight)?.cancel()
        logger.trace { "Removed ${transaction.hash} from the voter queue" }
    }

    override fun clear() {
        consensusMap.clear()
        pending.clear()
    }

    private data class Consensus(
        val transaction: Transaction,
        val consensusTimestamp: Instant,
    ) {
        val hash: AttoHash
            get() = transaction.hash
    }
}
