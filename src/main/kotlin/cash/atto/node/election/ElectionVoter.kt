package cash.atto.node.election

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoSignedVote
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoUnit
import cash.atto.commons.AttoVote
import cash.atto.commons.toAttoVersion
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountUpdated
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.PublicKeyHeight
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.TransactionRepository
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVotePush
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class ElectionVoter(
    private val thisNode: AttoNode,
    private val signer: AttoSigner,
    private val voteWeighter: VoteWeighter,
    private val transactionRepository: TransactionRepository,
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        val MIN_WEIGHT = AttoAmount.from(AttoUnit.ATTO, BigDecimal.valueOf(1_000_000).toString()) // 1M
        val finalVoteTimestamp = AttoVote.finalTimestamp.toJavaInstant()
    }

    private val mutex = Mutex()

    private val transactions = HashMap<PublicKeyHeight, Transaction>()
    private val agreements = HashSet<PublicKeyHeight>()

    @EventListener
    suspend fun process(event: ElectionStarted) =
        mutex.withLock {
            val transaction = event.transaction
            if (transactions[transaction.toPublicKeyHeight()] == null) {
                consensed(transaction)
            }
        }

    @EventListener
    suspend fun process(event: ElectionConsensusChanged) =
        mutex.withLock {
            consensed(event.transaction)
        }

    private suspend fun consensed(transaction: Transaction) {
        val publicKeyHeight = transaction.toPublicKeyHeight()

        val oldTransaction = transactions[publicKeyHeight]
        if (oldTransaction != transaction) {
            logger.trace { "Consensus changed from ${oldTransaction?.hash} to ${transaction.hash}" }
            transactions[publicKeyHeight] = transaction
            vote(transaction, Instant.now())
        }
    }

    @EventListener
    suspend fun process(event: ElectionConsensusReached) =
        mutex.withLock {
            val transaction = event.transaction

            val publicKeyHeight = transaction.toPublicKeyHeight()
            if (!agreements.contains(publicKeyHeight)) {
                agreements.add(publicKeyHeight)
                vote(transaction, Instant.now())
                remove(event.transaction)
            } else {
                logger.trace { "Consensus already reached for ${event.transaction}" }
            }
        }

    @EventListener
    suspend fun process(event: ElectionExpiring) =
        mutex.withLock {
            vote(event.transaction, Instant.now())
        }

    @EventListener
    suspend fun process(event: ElectionExpired) =
        mutex.withLock {
            remove(event.transaction)
        }

    @EventListener
    suspend fun process(event: AccountUpdated) =
        mutex.withLock {
            if (event.source == TransactionSource.ELECTION) {
                vote(event.transaction, finalVoteTimestamp)
            }
        }

    @EventListener
    suspend fun process(event: TransactionRejected) {
        if (event.reason != TransactionRejectionReason.OLD_TRANSACTION) {
            return
        }
        val transaction = event.transaction
        if (transactionRepository.existsById(transaction.hash)) {
            mutex.withLock {
                vote(transaction, finalVoteTimestamp)
            }
        }
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
        transactions.remove(publicKeyHeight)
        agreements.remove(publicKeyHeight)
        logger.trace { "Removed ${transaction.hash} from the voter queue" }
    }

    override fun clear() {
        transactions.clear()
        agreements.clear()
    }
}
