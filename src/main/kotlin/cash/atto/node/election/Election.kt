package cash.atto.node.election

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.node.CacheSupport
import cash.atto.node.Event
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountUpdated
import cash.atto.node.transaction.PublicKeyHeight
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionValidated
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.TimeUnit

@Service
class Election(
    private val properties: ElectionProperties,
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()

    private val publicKeyHeightElectionMap = HashMap<PublicKeyHeight, PublicKeyHeightElection>()

    fun getSize(): Int = publicKeyHeightElectionMap.size

    fun getElections(): Map<PublicKeyHeight, PublicKeyHeightElection> = publicKeyHeightElectionMap

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

    @EventListener
    suspend fun process(event: AccountUpdated) =
        mutex.withLock {
            val transaction = event.transaction
            publicKeyHeightElectionMap.remove(transaction.toPublicKeyHeight())?.let {
                logger.debug { "Stopped election for ${transaction.hash} since transaction was just saved" }
            }
        }

    private suspend fun start(
        account: Account,
        transaction: Transaction,
    ) = mutex.withLock {
        publicKeyHeightElectionMap.compute(transaction.toPublicKeyHeight()) { _, v ->
            val publicKeyHeightElection =
                v ?: PublicKeyHeightElection(account) {
                    voteWeighter.getMinimalConfirmationWeight()
                }
            publicKeyHeightElection.add(transaction)
            publicKeyHeightElection
        }

        logger.trace { "Started election for $transaction" }

        eventPublisher.publish(ElectionStarted(account, transaction))
    }

    private suspend fun process(
        transaction: Transaction,
        vote: Vote,
    ) = mutex.withLock {
        val publicKeyHeight = transaction.toPublicKeyHeight()

        logger.trace { "Processing $vote" }

        val publicKeyHeightElection = publicKeyHeightElectionMap[publicKeyHeight]

        if (publicKeyHeightElection == null) {
            logger.trace { "Election for $publicKeyHeight not found. Vote will be ignored" }
            return@withLock
        }

        if (!publicKeyHeightElection.add(vote)) {
            logger.trace { "Vote is old and it won't be considered in the election $publicKeyHeight $vote" }
            return@withLock
        }

        val account = publicKeyHeightElection.account

        val consensusTransactionElection = publicKeyHeightElection.getConsensus()
        if (consensusTransactionElection != null) {
            val minimalConfirmationWeight = voteWeighter.getMinimalConfirmationWeight()
            val totalWeight = consensusTransactionElection.totalWeight
            val finalTransaction = consensusTransactionElection.transaction
            val votes = consensusTransactionElection.votes.values
            logger.trace {
                "Consensus reached because totalWeight($totalWeight) > minimalConfirmationWeight($minimalConfirmationWeight). " +
                    "Transaction ${finalTransaction.hash} was chosen by ${votes.map { "${it.publicKey}=${it.weight}" }}."
            }
            publicKeyHeightElectionMap.remove(transaction.toPublicKeyHeight())
            eventPublisher.publish(ElectionConsensusReached(account, finalTransaction, votes))
            return@withLock
        }

        val provisionalTransactionElection = publicKeyHeightElection.getProvisionalLeader()
        logger.trace { "Transaction ${provisionalTransactionElection.transaction.hash} is the current provisional leader." }
        eventPublisher.publish(ElectionConsensusChanged(account, provisionalTransactionElection.transaction))
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun processExpiring() =
        mutex.withLock {
            val minimalTimestamp = Instant.now().minusSeconds(properties.expiringAfterTimeInSeconds!!)

            publicKeyHeightElectionMap
                .values
                .toList()
                .filter { it.getProvisionalLeader().transaction.receivedAt < minimalTimestamp }
                .forEach {
                    val transaction = it.getProvisionalLeader().transaction
                    logger.trace { "Expiring $transaction" }
                    eventPublisher.publish(ElectionExpiring(it.account, transaction))
                }
        }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun stopObservingStaled() =
        mutex.withLock {
            val minimalTimestamp = Instant.now().minusSeconds(properties.expiredAfterTimeInSeconds!!)

            publicKeyHeightElectionMap
                .values
                .toList()
                .filter { it.getProvisionalLeader().transaction.receivedAt < minimalTimestamp }
                .forEach {
                    val transaction = it.getProvisionalLeader().transaction
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
    private val minimalConfirmationWeightProvider: () -> AttoAmount,
) {
    private val transactionElectionMap = HashMap<AttoHash, TransactionElection>()

    fun add(transaction: Transaction) {
        if (transactionElectionMap.containsKey(transaction.hash)) {
            return
        }

        transactionElectionMap[transaction.hash] =
            TransactionElection(transaction, minimalConfirmationWeightProvider)
    }

    fun add(vote: Vote): Boolean {
        val transactionElection =
            transactionElectionMap[vote.blockHash]
                ?: throw IllegalStateException("No election for block ${vote.blockHash}")

        if (!transactionElection.add(vote)) {
            return false
        }

        transactionElectionMap
            .values
            .asSequence()
            .filter { it.transaction.hash != vote.blockHash }
            .forEach { it.remove(vote) }

        return true
    }

    fun getProvisionalLeader(): TransactionElection =
        transactionElectionMap
            .values
            .maxBy { it.totalWeight }

    fun getConsensus(): TransactionElection? =
        transactionElectionMap
            .values
            .firstOrNull { it.isConsensusReached() }
}

class TransactionElection(
    val transaction: Transaction,
    private val minimalConfirmationWeightProvider: () -> AttoAmount,
) {
    @Volatile
    var totalWeight = AttoAmount.MIN
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
            val remainingWeight = minimalConfirmationWeight - totalWeight
            totalWeight += if (vote.weight < remainingWeight) vote.weight else remainingWeight
        }

        return true
    }

    fun isConsensusReached(): Boolean {
        val minimalConfirmationWeight = minimalConfirmationWeightProvider.invoke()
        return totalWeight >= minimalConfirmationWeight
    }

    internal fun remove(vote: Vote): Vote? = votes.remove(vote.publicKey)
}

data class ElectionStarted(
    val account: Account,
    val transaction: Transaction,
) : Event

data class ElectionConsensusChanged(
    val account: Account,
    val transaction: Transaction,
) : Event

data class ElectionConsensusReached(
    val account: Account,
    val transaction: Transaction,
    val votes: Collection<Vote>,
) : Event

data class ElectionExpiring(
    val account: Account,
    val transaction: Transaction,
) : Event

data class ElectionExpired(
    val account: Account,
    val transaction: Transaction,
) : Event
