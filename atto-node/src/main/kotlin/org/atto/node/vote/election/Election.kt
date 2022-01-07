package org.atto.node.vote.election

import kotlinx.coroutines.*
import mu.KotlinLogging
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.transaction.TransactionValidated
import org.atto.node.vote.HashVoteValidated
import org.atto.node.vote.WeightedHashVote
import org.atto.node.vote.weight.VoteWeightService
import org.atto.protocol.transaction.PublicKeyHeight
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.vote.HashVote
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.*


@ExperimentalCoroutinesApi
@Service
class Election(
    private val properties: ElectionProperties,
    private val voteWeightService: VoteWeightService,
    private val observers: List<ElectionObserver>
) {
    private val logger = KotlinLogging.logger {}

    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val singleScope = CoroutineScope(singleDispatcher)

    private val transactionWeights = HashMap<AttoHash, TransactionWeight>()
    private val heightHashes = HashMap<PublicKeyHeight, LinkedList<AttoHash>>()
    private val heightVotes = HashMap<PublicKeyHeight, HashMap<AttoPublicKey, WeightedHashVote>>()

    @EventListener
    fun observe(transactionEvent: TransactionValidated) {
        val transaction = transactionEvent.transaction
        singleScope.launch {
            startObserving(transaction)
        }
    }

    @EventListener
    fun process(hashVoteValidated: HashVoteValidated) {
        val hashVote = hashVoteValidated.hashVote

        singleScope.launch {
            process(hashVote, voteWeightService.get(hashVote.vote.publicKey))
        }
    }

    suspend fun getTransactionWeights(): Map<AttoHash, TransactionWeight> {
        return withContext(singleDispatcher) {
            transactionWeights.entries.associate { it.key to it.value }
        }
    }

    suspend fun getActiveElections(): Map<PublicKeyHeight, HashMap<AttoPublicKey, WeightedHashVote>> {
        return withContext(singleDispatcher) {
            heightVotes.entries.associate { it.key to HashMap(it.value) }
        }
    }

    private suspend fun startObserving(transaction: Transaction) {
        transactionWeights[transaction.hash] = TransactionWeight(transaction)

        val publicKeyHeight = transaction.toPublicKeyHeight()
        heightHashes.compute(publicKeyHeight) { _, v ->
            var transactions = v
            if (transactions == null) {
                transactions = LinkedList<AttoHash>()
            }
            transactions.add(transaction.hash)
            transactions
        }

        heightVotes[publicKeyHeight] = HashMap()
        logger.trace { "Started observing $transaction" }

        observers.forEach { it.observed(transaction) }
    }

    private fun stopObserving(transaction: Transaction) {
        transactionWeights.remove(transaction.hash)

        val publicKeyHeight = transaction.toPublicKeyHeight()
        heightHashes.remove(publicKeyHeight)
        heightVotes.remove(publicKeyHeight)
    }

    private suspend fun process(hashVote: HashVote, weight: ULong) {
        val transactionWeight = transactionWeights[hashVote.hash] ?: return

        logger.trace { "Processing $hashVote" }

        val publicKeyHeight = transactionWeight.transaction.toPublicKeyHeight()
        val electionVotes = heightVotes[publicKeyHeight]!!

        val oldWeightedHashVote = electionVotes[hashVote.vote.publicKey]
        if (oldWeightedHashVote != null && oldWeightedHashVote.hashVote.vote.timestamp >= hashVote.vote.timestamp) {
            logger.trace { "Ignored old vote $hashVote" }
            return
        }

        if (oldWeightedHashVote != null) {
            val anotherTransactionWeight = transactionWeights[oldWeightedHashVote.hashVote.hash]!!
            anotherTransactionWeight.remove(oldWeightedHashVote)
        }

        val newWeightedHashVote = WeightedHashVote(hashVote, weight)
        electionVotes[hashVote.vote.publicKey] = newWeightedHashVote
        transactionWeight.add(newWeightedHashVote)

        consensed(publicKeyHeight)

        if (transactionWeight.isAgreementAbove(voteWeightService.getMinimalConfirmationWeight())) {
            observers.forEach { it.agreed(transactionWeight.transaction) }
        }

        if (transactionWeight.isFinalAbove(voteWeightService.getMinimalConfirmationWeight())) {
            val transaction = transactionWeight.transaction
            stopObserving(transaction)

            val hashVotes = transactionWeight.weightedHashVotes.asSequence()
                .filter { it.isFinal() }
                .map { it.hashVote }
                .toList()

            observers.forEach { it.confirmed(transaction, hashVotes) }
        }
    }

    private suspend fun consensed(publicKeyHeight: PublicKeyHeight) {
        val consensedTransactionWeight = getConsensus(publicKeyHeight)

        observers.forEach { it.consensed(consensedTransactionWeight.transaction) }
    }

    private fun getConsensus(publicKeyHeight: PublicKeyHeight): TransactionWeight {
        return heightHashes[publicKeyHeight]!!.asSequence()
            .map { transactionWeights[it]!! }
            .maxByOrNull { it.totalWeight }!!
    }


    @Scheduled(cron = "0 0/1 * * * *")
    fun processStaling() {
        val minimalTimestamp = Instant.now().minusSeconds(properties.stalingAfterTimeInSeconds!!)
        singleScope.launch {
            transactionWeights.values.asSequence()
                .map { it.transaction }
                .filter { it.receivedTimestamp < minimalTimestamp }
                .map { it.toPublicKeyHeight() }
                .distinct()
                .map { getConsensus(it).transaction }
                .forEach { transaction ->
                    logger.trace { "Staling $transaction" }
                    observers.forEach {
                        it.staling(transaction)
                    }
                }
        }
    }

    @Scheduled(cron = "0 0/1 * * * * *")
    fun stopObservingStaled() {
        val minimalTimestamp = Instant.now().minusSeconds(properties.staledAfterTimeInSeconds!!)
        singleScope.launch {
            transactionWeights.values.asSequence()
                .map { it.transaction }
                .filter { it.receivedTimestamp < minimalTimestamp }
                .forEach { transaction ->
                    logger.trace { "Staled $transaction" }
                    stopObserving(transaction)
                    observers.forEach {
                        it.staled(transaction)
                    }
                }
        }
    }
}

data class TransactionWeight(val transaction: Transaction) {
    var totalWeight: ULong = 0UL
    var totalFinalWeight: ULong = 0UL

    internal val weightedHashVotes = HashSet<WeightedHashVote>()

    internal fun add(weightedHashVote: WeightedHashVote) {
        weightedHashVotes.add(weightedHashVote)
        totalWeight += weightedHashVote.weight

        if (weightedHashVote.isFinal()) {
            totalFinalWeight += weightedHashVote.weight
        }
    }

    internal fun remove(weightedHashVote: WeightedHashVote) {
        weightedHashVotes.remove(weightedHashVote)
        totalWeight -= weightedHashVote.weight
    }

    internal fun isAgreementAbove(weight: ULong): Boolean {
        return totalWeight >= weight
    }

    internal fun isFinalAbove(weight: ULong): Boolean {
        return totalFinalWeight >= weight
    }
}