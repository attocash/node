package org.atto.node.bootstrap.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.EventPublisher
import org.atto.node.bootstrap.TransactionDiscovered
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejected
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.vote.Vote
import org.atto.node.vote.VoteDropReason
import org.atto.node.vote.VoteDropped
import org.atto.node.vote.weight.VoteWeighter
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class DependencyDiscoverer(
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher
) {
    private val logger = KotlinLogging.logger {}

    private val transactionHolderMap = FixedSizeHashMap<AttoHash, TransactionHolder>(50_000)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    @EventListener
    @Async
    fun process(event: TransactionRejected) {
        val reason = event.reason
        if (!reason.recoverable) {
            return
        }

        runBlocking {
            add(event.reason, event.transaction)
        }
    }

    suspend fun add(reason: TransactionRejectionReason?, transaction: Transaction) = withContext(singleDispatcher) {
        val transactionHolder = TransactionHolder(reason, transaction)
        transactionHolderMap[transaction.hash] = transactionHolder
        logger.debug { "Transaction rejected but added to the discovery queue. $transaction" }
    }


    @EventListener
    @Async
    fun process(event: VoteDropped) {
        if (event.reason != VoteDropReason.TRANSACTION_DROPPED) {
            return
        }

        runBlocking {
            process(event.vote)
        }
    }

    suspend fun process(vote: Vote) = withContext(singleDispatcher) {
        if (!vote.isFinal()) {
            return@withContext
        }

        val hash = vote.hash

        val holder = transactionHolderMap[hash] ?: return@withContext

        holder.add(vote)

        val weight = holder.getWeight()
        val minimalConfirmationWeight = voteWeighter.getMinimalConfirmationWeight()
        if (weight >= minimalConfirmationWeight) {
            transactionHolderMap.remove(hash)
            eventPublisher.publish(
                TransactionDiscovered(
                    holder.reason,
                    holder.transaction,
                    holder.votes.values.toList()
                )
            )
        }
    }
}

private class TransactionHolder(
    val reason: TransactionRejectionReason?,
    val transaction: Transaction
) {
    val votes = HashMap<AttoPublicKey, Vote>()

    fun add(vote: Vote) {
        votes[vote.publicKey] = vote
    }

    fun getWeight(): AttoAmount {
        return votes.values.asSequence()
            .map { it.weight }
            .fold(AttoAmount.MIN) { acc, weight -> acc + weight }
    }

}

private class FixedSizeHashMap<K, V>(private val maxSize: Int) : LinkedHashMap<K, V>(maxSize) {
    override fun removeEldestEntry(eldest: Map.Entry<K, V>?): Boolean {
        return size > maxSize
    }
}