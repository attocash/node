package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteDropReason
import cash.atto.node.vote.VoteDropped
import cash.atto.node.vote.weight.VoteWeighter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class DependencyDiscoverer(
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()
    private val transactionHolderMap = FixedSizeHashMap<AttoHash, TransactionHolder>(50_000)

    @EventListener
    suspend fun process(event: TransactionRejected) {
        val reason = event.reason
        if (!reason.recoverable) {
            return
        }

        add(event.reason, event.transaction)
    }

    suspend fun add(
        reason: TransactionRejectionReason?,
        transaction: Transaction,
    ) = mutex.withLock {
        val transactionHolder = TransactionHolder(reason, transaction)
        transactionHolderMap[transaction.hash] = transactionHolder
        logger.debug { "Transaction rejected but added to the discovery queue. $transaction" }
    }

    @EventListener
    suspend fun process(event: VoteDropped) {
        if (event.reason != VoteDropReason.TRANSACTION_DROPPED) {
            return
        }

        process(event.vote)
    }

    suspend fun process(vote: Vote) =
        mutex.withLock {
            if (!vote.isFinal()) {
                return@withLock
            }

            val hash = vote.blockHash

            val holder = transactionHolderMap[hash] ?: return@withLock

            holder.add(vote)

            val weight = holder.getWeight()
            val minimalConfirmationWeight = voteWeighter.getMinimalConfirmationWeight()
            if (weight >= minimalConfirmationWeight) {
                transactionHolderMap.remove(hash)
                logger.debug { "Discovered approved transaction that's missing some dependency $hash" }
                eventPublisher.publish(
                    TransactionDiscovered(
                        holder.reason,
                        holder.transaction,
                        holder.votes.values.toList(),
                    ),
                )
            }
        }
}

private class TransactionHolder(
    val reason: TransactionRejectionReason?,
    val transaction: Transaction,
) {
    val votes = HashMap<AttoPublicKey, Vote>()

    fun add(vote: Vote) {
        votes[vote.publicKey] = vote
    }

    fun getWeight(): AttoAmount =
        votes
            .values
            .asSequence()
            .map { it.weight }
            .fold(AttoAmount.MIN) { acc, weight -> acc + weight }
}

private class FixedSizeHashMap<K, V>(
    private val maxSize: Int,
) : LinkedHashMap<K, V>(maxSize) {
    override fun removeEldestEntry(eldest: Map.Entry<K, V>?): Boolean = size > maxSize
}
