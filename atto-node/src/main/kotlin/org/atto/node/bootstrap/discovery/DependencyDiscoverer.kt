package org.atto.node.bootstrap.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejected
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.node.vote.Vote
import org.atto.node.vote.VoteDropReason
import org.atto.node.vote.VoteDropped
import org.atto.node.vote.weight.VoteWeightService
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class DependencyDiscoverer(
    converters: List<DependencyProcessor>,
    private val voteWeightService: VoteWeightService,
) {
    private val logger = KotlinLogging.logger {}

    private val converterMap = converters.associateBy { it.type() }
    private val transactionHolderMap = FixedSizeHashMap<AttoHash, TransactionHolder>(10_000)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    @EventListener
    @Async
    fun process(event: TransactionRejected) {
        val reason = event.reason
        if (!converterMap.contains(reason)) {
            return
        }

        runBlocking(singleDispatcher) {
            val transaction = event.transaction
            val transactionHolder = TransactionHolder(reason, transaction)
            transactionHolderMap[transaction.hash] = transactionHolder
            logger.debug { "Transaction rejected but added to the discovery queue. $transaction" }
        }
    }


    @EventListener
    @Async
    fun process(event: VoteDropped) = runBlocking(singleDispatcher) {
        if (event.reason != VoteDropReason.TRANSACTION_DROPPED) {
            return@runBlocking
        }

        val hash = event.vote.hash
        val holder = transactionHolderMap[hash] ?: return@runBlocking

        val vote = event.vote
        if (!vote.isFinal()) {
            return@runBlocking
        }
        holder.add(vote)

        val weight = holder.getWeight()
        val minimalConfirmationWeight = voteWeightService.getMinimalConfirmationWeight()
        if (weight >= minimalConfirmationWeight) {
            transactionHolderMap.remove(hash)
            val processor = converterMap[holder.reason]!!
            processor.process(holder.transaction, holder.votes.values.toList())
        }
    }
}

private class TransactionHolder(
    val reason: TransactionRejectionReason,
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