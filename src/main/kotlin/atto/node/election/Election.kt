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
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.TimeUnit


@Service
class Election(
    private val properties: ElectionProperties,
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val publicKeyHeightElectionMap = HashMap<PublicKeyHeight, PublicKeyHeightElection>()

    @PreDestroy
    fun stop() {
        singleDispatcher.cancel()
    }

    fun getSize(): Int {
        return publicKeyHeightElectionMap.size
    }

    fun getElections(): Map<PublicKeyHeight, PublicKeyHeightElection> {
        return publicKeyHeightElectionMap
    }

    @EventListener
    suspend fun start(event: TransactionValidated) {
        val transaction = event.transaction
        start(event.account, transaction)
    }

    @EventListener
    suspend fun process(event: VoteValidated) {
        val vote = event.vote
        process(event.transaction, vote)
    }

    private suspend fun start(account: Account, transaction: Transaction) = withContext(singleDispatcher) {
        publicKeyHeightElectionMap.compute(transaction.toPublicKeyHeight()) { _, v ->
            val publicKeyHeightElection = v ?: PublicKeyHeightElection(account) {
                voteWeighter.getMinimalConfirmationWeight()
            }
            publicKeyHeightElection.add(transaction)
            publicKeyHeightElection
        }

        logger.trace { "Started election for $transaction" }

        eventPublisher.publish(ElectionStarted(account, transaction))
    }

    private suspend fun process(transaction: Transaction, vote: Vote) = withContext(singleDispatcher) {
        val publicKeyHeight = transaction.toPublicKeyHeight()

        val publicKeyHeightElection = publicKeyHeightElectionMap[publicKeyHeight] ?: return@withContext

        logger.trace { "Processing $vote" }

        if (!publicKeyHeightElection.add(vote)) {
            logger.trace { "Vote is old and it won't be considered in the election $publicKeyHeight $vote" }
            return@withContext
        }

        val account = publicKeyHeightElection.account

        val finalTransactionElection = publicKeyHeightElection.getFinalConsensus()
        if (finalTransactionElection != null) {
            val finalTransaction = finalTransactionElection.transaction
            val votes = finalTransactionElection.votes.values
            publicKeyHeightElectionMap.remove(transaction.toPublicKeyHeight())
            eventPublisher.publish(ElectionFinished(account, finalTransaction, votes))
            return@withContext
        }

        val consensusTransactionElection = publicKeyHeightElection.getConsensus()
        if (consensusTransactionElection != null) {
            eventPublisher.publish(ElectionConsensusReached(account, consensusTransactionElection.transaction))
            return@withContext
        }

        val currentConsensusTransactionElection = publicKeyHeightElection.getCurrentConsensus()
        eventPublisher.publish(ElectionConsensusChanged(account, currentConsensusTransactionElection.transaction))
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun processStaling() = withContext(singleDispatcher) {
        val minimalTimestamp = Instant.now().minusSeconds(properties.stalingAfterTimeInSeconds!!)

        publicKeyHeightElectionMap.values.asSequence()
            .filter { it.getCurrentConsensus().transaction.receivedAt < minimalTimestamp }
            .forEach {
                val transaction = it.getCurrentConsensus().transaction
                logger.trace { "Expiring $transaction" }
                publicKeyHeightElectionMap.remove(transaction.toPublicKeyHeight())
                eventPublisher.publish(ElectionExpiring(it.account, transaction))
            }
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun stopObservingStaled() = withContext(singleDispatcher) {
        val minimalTimestamp = Instant.now().minusSeconds(properties.staledAfterTimeInSeconds!!)

        publicKeyHeightElectionMap.values.asSequence()
            .filter { it.getCurrentConsensus().transaction.receivedAt < minimalTimestamp }
            .forEach {
                val transaction = it.getCurrentConsensus().transaction
                logger.trace { "Expired $transaction" }
                publicKeyHeightElectionMap.remove(transaction.toPublicKeyHeight())
                eventPublisher.publish(ElectionExpired(it.account, transaction))
            }
    }

    override fun clear() {
        publicKeyHeightElectionMap.clear()
    }
}

class PublicKeyHeightElection(
    val account: Account,
    private val minimalConfirmationWeightProvider: () -> AttoAmount
) {
    private val transactionElectionMap = HashMap<AttoHash, TransactionElection>()
    fun add(transaction: Transaction) {
        if (transactionElectionMap.containsKey(transaction.hash)) {
            throw IllegalStateException("Transaction for block ${transaction.hash} already started")
        }

        transactionElectionMap[transaction.hash] =
            TransactionElection(transaction, minimalConfirmationWeightProvider)
    }

    fun add(vote: Vote): Boolean {
        val transactionElection = transactionElectionMap[vote.blockHash]
            ?: throw IllegalStateException("No election for block ${vote.blockHash}")

        if (!transactionElection.add(vote)) {
            return false
        }

        transactionElectionMap.values.asSequence()
            .filter { it.transaction.hash != vote.blockHash }
            .forEach { it.remove(vote) }

        return true
    }

    fun getCurrentConsensus(): TransactionElection {
        return transactionElectionMap.values
            .maxBy { it.totalWeight }
    }

    fun getConsensus(): TransactionElection? {
        return transactionElectionMap.values
            .firstOrNull { it.isConsensusReached() }
    }

    fun getFinalConsensus(): TransactionElection? {
        return transactionElectionMap.values
            .firstOrNull { it.isConfirmed() }
    }
}

class TransactionElection(
    val transaction: Transaction,
    private val minimalConfirmationWeightProvider: () -> AttoAmount
) {
    @Volatile
    var totalWeight = AttoAmount.MIN
        private set

    @Volatile
    var totalFinalWeight = AttoAmount.MIN
        private set

    internal val votes = HashMap<AttoPublicKey, Vote>()

    internal fun add(vote: Vote): Boolean {
        val oldVote = votes[vote.publicKey]

        if (oldVote != null && oldVote.timestamp >= vote.timestamp) {
            return false
        }

        votes[vote.publicKey] = vote

        /**
         * Due to async nature of voting the cached voting weight from vote may exceed the max atto amount. This issue
         * is unlikely to happen in the live environment but very likely to happen locally.
         */
        val minimalConfirmationWeight = minimalConfirmationWeightProvider.invoke()
        if (totalWeight < minimalConfirmationWeight) {
            totalWeight += vote.weight
        }

        if (vote.isFinal() && totalFinalWeight < minimalConfirmationWeight) {
            totalFinalWeight += vote.weight
        }

        return true
    }

    fun isConsensusReached(): Boolean {
        val minimalConfirmationWeight = minimalConfirmationWeightProvider.invoke()
        return totalWeight >= minimalConfirmationWeight
    }

    fun isConfirmed(): Boolean {
        val minimalConfirmationWeight = minimalConfirmationWeightProvider.invoke()
        return totalFinalWeight >= minimalConfirmationWeight
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
    val votes: Collection<Vote>
) : Event

data class ElectionExpiring(
    val account: Account,
    val transaction: Transaction
) : Event

data class ElectionExpired(
    val account: Account,
    val transaction: Transaction
) : Event