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
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.time.Duration

@Component
class DependencyDiscoverer(
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()
    private val transactionHolderMap = FixedSizeHashMap<AttoHash, TransactionHolder>(50_000)

    /*
     * This buffer is mainly used in tests to handle out-of-order votes that arrive before their corresponding transactions.
     * In live environments, this situation is very unlikely due to the deterministic flow of message propagation,
     * but since Atto operates asynchronously across peers, it’s not entirely impossible.
     *
     * Entries expire quickly (1 minute) and are weighted by the number of votes to limit memory usage.
     */
    private val outOfOrderBuffer =
        Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumWeight(1000)
            .weigher<AttoHash, List<Vote>> { _, votes ->
                votes.size
            }
            .build<AttoHash, ArrayList<Vote>>()
            .asMap()

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

        outOfOrderBuffer.remove(transaction.hash)?.forEach { vote ->
            process(vote)
        }
    }

    @EventListener
    suspend fun process(event: VoteDropped) {
        if (event.reason != VoteDropReason.TRANSACTION_DROPPED) {
            return
        }

        if (!event.vote.isFinal()) {
            return
        }

        outOfOrderBuffer.compute(event.vote.blockHash) { _, v ->
            val votes = v ?: ArrayList()
            votes.add(event.vote)
            return@compute votes
        }

        process(event.vote)
    }

    suspend fun process(vote: Vote): Unit =
        mutex.withLock {
            val hash = vote.blockHash

            val holder = transactionHolderMap[hash] ?: return

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

            return
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
