package atto.node.election

import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.BroadcastNetworkMessage
import atto.node.network.BroadcastStrategy
import atto.node.network.NetworkMessagePublisher
import atto.node.transaction.*
import atto.node.vote.Vote
import atto.node.vote.VoteValidated
import atto.node.vote.weight.VoteWeighter
import atto.protocol.vote.AttoVote
import atto.protocol.vote.AttoVotePush
import cash.atto.commons.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Instant

@Service
class ElectionVoter(
    private val thisNode: atto.protocol.AttoNode,
    private val privateKey: AttoPrivateKey,
    private val voteWeighter: VoteWeighter,
    private val transactionRepository: TransactionRepository,
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val minWeight = AttoAmount.from(AttoUnit.ATTO, BigDecimal.valueOf(1_000_000_000)) // 1M

    private val transactions = HashMap<PublicKeyHeight, Transaction>()
    private val agreements = HashSet<PublicKeyHeight>()

    @EventListener
    suspend fun process(event: ElectionStarted) = withContext(singleDispatcher) {
        val transaction = event.transaction
        if (transactions[transaction.toPublicKeyHeight()] == null) {
            consensed(transaction)
        }
    }

    @EventListener
    suspend fun process(event: ElectionConsensusChanged) = withContext(singleDispatcher) {
        consensed(event.transaction)
    }

    private suspend fun consensed(transaction: Transaction) {
        val publicKeyHeight = transaction.toPublicKeyHeight()

        val oldTransaction = transactions[publicKeyHeight]
        if (oldTransaction != transaction) {
            transactions[publicKeyHeight] = transaction
            vote(transaction, Instant.now())
        }
    }


    @EventListener
    suspend fun process(event: ElectionConsensusReached) = withContext(singleDispatcher) {
        val transaction = event.transaction

        val publicKeyHeight = transaction.toPublicKeyHeight()
        if (!agreements.contains(publicKeyHeight)) {
            agreements.add(publicKeyHeight)
            vote(transaction, AttoVote.finalTimestamp.toJavaInstant())
        } else {
            logger.trace { "Consensus already reached for ${event.transaction}" }
        }
    }

    @EventListener
    suspend fun process(event: ElectionFinished) = withContext(singleDispatcher) {
        remove(event.transaction)
    }

    @EventListener
    suspend fun process(event: ElectionExpiring) = withContext(singleDispatcher) {
        vote(event.transaction, Instant.now())
    }

    @EventListener
    suspend fun process(event: ElectionExpired) = withContext(singleDispatcher) {
        remove(event.transaction)
    }

    @EventListener
    suspend fun process(event: TransactionRejected) {
        if (event.reason != TransactionRejectionReason.OLD_TRANSACTION) {
            return
        }
        val transaction = event.transaction
        if (transactionRepository.existsById(transaction.hash)) {
            withContext(singleDispatcher) {
                vote(transaction, AttoVote.finalTimestamp.toJavaInstant())
            }
        }
    }

    private fun vote(transaction: Transaction, timestamp: Instant) {
        val weight = voteWeighter.get()
        if (!canVote(weight)) {
            logger.trace { "This $thisNode can't vote" }
            return
        }

        val voteHash = AttoHash.hashVote(
            transaction.hash,
            AttoAlgorithm.V1,
            timestamp.toKotlinInstant()
        )
        val attoVote = AttoVote(
            timestamp = timestamp.toKotlinInstant(),
            algorithm = thisNode.algorithm,
            publicKey = thisNode.publicKey,
            signature = privateKey.sign(voteHash)
        )
        val votePush = AttoVotePush(
            blockHash = transaction.hash,
            vote = attoVote
        )

        val strategy = if (attoVote.isFinal()) {
            BroadcastStrategy.EVERYONE
        } else {
            BroadcastStrategy.VOTERS
        }

        logger.debug { "Sending to $strategy $attoVote" }

        eventPublisher.publish(VoteValidated(transaction, Vote.from(weight, transaction.hash, attoVote)))
        messagePublisher.publish(BroadcastNetworkMessage(strategy, emptySet(), votePush))
    }

    private fun canVote(weight: AttoAmount): Boolean {
        return thisNode.isVoter() && weight >= minWeight
    }

    private suspend fun remove(transaction: Transaction) {
        val publicKeyHeight = transaction.toPublicKeyHeight()
        transactions.remove(publicKeyHeight)
        agreements.remove(publicKeyHeight)
    }

    override fun clear() {
        transactions.clear()
        agreements.clear()
    }

}