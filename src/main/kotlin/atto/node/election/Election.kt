package atto.node.election

import atto.node.CacheSupport
import atto.node.Event
import atto.node.EventPublisher
import atto.node.account.Account
import atto.node.transaction.PublicKeyHeight
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionValidated
import atto.node.vote.Vote
import atto.node.vote.VoteValidated
import atto.node.vote.weight.VoteWeighter
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant


@Service
class Election(
    private val properties: ElectionProperties,
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val transactionWeighters = HashMap<PublicKeyHeight, HashMap<AttoHash, TransactionWeighter>>()

    fun getSize(): Int {
        return transactionWeighters.size
    }

    fun getElections(): Map<PublicKeyHeight, Map<AttoHash, TransactionWeighter>> {
        return transactionWeighters
    }

    @EventListener
    @Async
    fun start(event: TransactionValidated) = runBlocking {
        val transaction = event.transaction
        start(event.account, transaction)
    }

    @EventListener
    @Async
    fun process(event: VoteValidated) = runBlocking {
        val vote = event.vote
        process(event.transaction, vote)
    }

    suspend fun start(account: Account, transaction: Transaction) {
        withContext(singleDispatcher) {
            transactionWeighters.compute(transaction.toPublicKeyHeight()) { _, v ->
                val weighter = v ?: HashMap()
                weighter[transaction.hash] = TransactionWeighter(account, transaction)
                weighter
            }
        }

        logger.trace { "Started election for $transaction" }

        eventPublisher.publish(ElectionStarted(account, transaction))
    }

    private fun isObserving(transaction: Transaction): Boolean {
        return transactionWeighters.containsKey(transaction.toPublicKeyHeight())
    }

    private fun stopObserving(transaction: Transaction) {
        transactionWeighters.remove(transaction.toPublicKeyHeight())
    }

    private suspend fun process(transaction: Transaction, vote: Vote) = withContext(singleDispatcher) {
        val publicKeyHeight = transaction.toPublicKeyHeight()

        val transactionWeightMap = transactionWeighters[publicKeyHeight] ?: return@withContext

        logger.trace { "Processing $vote" }
        /**
         * Weighter should never be null because votes are just broadcasted to active election
         */
        val weighter = transactionWeightMap[vote.hash]!!

        weighter.add(vote)

        transactionWeightMap.values.asSequence()
            .filter { it != weighter }
            .forEach { _ -> weighter.remove(vote) }

        consensed(publicKeyHeight)

        val minimalConfirmationWeight = voteWeighter.getMinimalConfirmationWeight()

        if (isObserving(transaction) && weighter.totalWeight() >= minimalConfirmationWeight) {
            eventPublisher.publish(ElectionConsensusReached(weighter.account, weighter.transaction))
        }

        if (isObserving(transaction) && weighter.totalFinalWeight() >= minimalConfirmationWeight) {
            stopObserving(transaction)

            val votes = weighter.votes.values.filter { it.isFinal() }

            eventPublisher.publish(ElectionFinished(weighter.account, weighter.transaction, votes))
        }
    }

    private fun getConsensus(publicKeyHeight: PublicKeyHeight): TransactionWeighter {
        return transactionWeighters[publicKeyHeight]!!.values
            .maxByOrNull { it.totalWeight() }!!
    }

    private suspend fun consensed(publicKeyHeight: PublicKeyHeight) {
        val weighter = getConsensus(publicKeyHeight)
        eventPublisher.publish(ElectionConsensusChanged(weighter.account, weighter.transaction))
    }

    @Scheduled(cron = "0 0/1 * * * *")
    fun processStaling() {
        val minimalTimestamp = Instant.now().minusSeconds(properties.stalingAfterTimeInSeconds!!)

        runBlocking(singleDispatcher) {
            transactionWeighters.values.asSequence()
                .flatMap { it.values }
                .map { it.transaction }
                .filter { it.receivedAt < minimalTimestamp }
                .map { it.toPublicKeyHeight() }
                .distinct()
                .map { getConsensus(it) }
                .forEach { weighter ->
                    logger.trace { "Staling ${weighter.transaction}" }
                    eventPublisher.publish(ElectionExpiring(weighter.account, weighter.transaction))
                }
        }
    }

    @Scheduled(cron = "0 0/1 * * * *")
    fun stopObservingStaled() {
        val minimalTimestamp = Instant.now().minusSeconds(properties.staledAfterTimeInSeconds!!)

        runBlocking(singleDispatcher) {
            transactionWeighters.values.asSequence()
                .flatMap { it.values }
                .filter { it.transaction.receivedAt < minimalTimestamp }
                .forEach { weighter ->
                    logger.trace { "Staled ${weighter.transaction}" }
                    stopObserving(weighter.transaction)
                    eventPublisher.publish(ElectionExpired(weighter.account, weighter.transaction))
                }
        }
    }

    override fun clear() {
        transactionWeighters.clear()
    }
}

data class TransactionWeighter(val account: Account, val transaction: Transaction) {
    internal val votes = HashMap<AttoPublicKey, Vote>()

    internal fun add(vote: Vote): Boolean {
        val oldVote = votes[vote.publicKey]

        if (oldVote != null && oldVote.timestamp >= vote.timestamp) {
            return false
        }

        votes[vote.publicKey] = vote

        return true
    }

    internal fun totalWeight(): AttoAmount {
        return votes.values.asSequence()
            .map { it.weight }
            .fold(AttoAmount.MIN) { a1, a2 -> a1 + a2 }
    }

    internal fun totalFinalWeight(): AttoAmount {
        return votes.values.asSequence()
            .filter { it.isFinal() }
            .map { it.weight }
            .fold(AttoAmount.MIN) { a1, a2 -> a1 + a2 }
    }

    internal fun remove(vote: Vote): Vote? {
        return votes.remove(vote.publicKey)
    }
}

data class ElectionStarted(
    val account: Account,
    val transaction: Transaction
) : Event

data class ElectionConsensusChanged(
    val account: Account,
    val transaction: Transaction
) : Event

data class ElectionConsensusReached(
    val account: Account,
    val transaction: Transaction
) : Event

data class ElectionFinished(
    val account: Account,
    val transaction: Transaction,
    val votes: List<Vote>
) : Event

data class ElectionExpiring(
    val account: Account,
    val transaction: Transaction
) : Event

data class ElectionExpired(
    val account: Account,
    val transaction: Transaction
) : Event