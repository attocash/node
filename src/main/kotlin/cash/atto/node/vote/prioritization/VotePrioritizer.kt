package cash.atto.node.vote.prioritization

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.node.CacheSupport
import cash.atto.node.DuplicateDetector
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.ElectionExpired
import cash.atto.node.election.ElectionStarted
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteDropReason
import cash.atto.node.vote.VoteDropped
import cash.atto.node.vote.VoteReceived
import cash.atto.node.vote.VoteRejected
import cash.atto.node.vote.VoteRejectionReason
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.prioritization.VoteQueue.TransactionVote
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

@Service
class VotePrioritizer(
    properties: VotePrioritizationProperties,
    private val eventPublisher: EventPublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()
    private val queue = VoteQueue(properties.queueMaxSize!!)

    private val activeElections = ConcurrentHashMap<AttoHash, Transaction>()

    private val duplicateDetector = DuplicateDetector<AttoHash>(1.minutes)

    private val rejectedTransactionCache =
        Caffeine
            .newBuilder()
            .maximumSize(10_000)
            .build<AttoHash, AttoHash>()
            .asMap()

    private val voteBuffer: MutableMap<AttoHash, MutableMap<AttoPublicKey, Vote>> =
        Caffeine
            .newBuilder()
            .maximumWeight(10_000)
            .weigher { _: AttoHash, v: MutableMap<AttoPublicKey, Vote> -> v.size }
            .evictionListener { _: AttoHash?, votes: MutableMap<AttoPublicKey, Vote>?, _ ->
                votes?.values?.forEach {
                    eventPublisher.publish(VoteDropped(it, VoteDropReason.NO_ELECTION))
                }
            }.build<AttoHash, MutableMap<AttoPublicKey, Vote>>()
            .asMap()

    fun getQueueSize(): Int = queue.getSize()

    fun getBufferSize(): Int = voteBuffer.size

    @EventListener
    suspend fun process(event: ElectionStarted) {
        val transaction = event.transaction

        val unbufferedVotes =
            mutex.withLock {
                activeElections[transaction.hash] = transaction
                rejectedTransactionCache.remove(transaction.hash)
                voteBuffer.remove(transaction.hash)?.values
            }

        unbufferedVotes?.forEach {
            logger.trace { "Unbuffered vote and ready to be prioritized. $it" }
            add(it)
        }
    }

    @EventListener
    suspend fun process(event: TransactionRejected) {
        val hash = event.transaction.hash
        val votes =
            mutex.withLock {
                rejectedTransactionCache[hash] = hash
                voteBuffer.remove(hash)?.values
            }
        votes?.forEach {
            eventPublisher.publish(VoteDropped(it, VoteDropReason.TRANSACTION_DROPPED))
        }
    }

    @EventListener
    fun process(event: AccountUpdated) {
        activeElections.remove(event.transaction.hash)
    }

    @EventListener
    fun process(event: ElectionExpired) {
        activeElections.remove(event.transaction.hash)
    }

    @EventListener
    suspend fun add(event: VoteReceived) {
        val vote = event.vote

        if (duplicateDetector.isDuplicate(vote.hash)) {
            logger.trace { "Ignored duplicated $vote" }
            return
        }
        val rejectionReason = validate(vote)
        if (rejectionReason != null) {
            eventPublisher.publish(VoteRejected(rejectionReason, vote))
            return
        }

        add(vote)
    }

    private suspend fun add(vote: Vote) {
        val dropped =
            mutex.withLock {
                val transaction = activeElections[vote.blockHash]
                if (transaction != null) {
                    logger.trace { "Queued for prioritization. $vote" }

                    queue.add(TransactionVote(transaction, vote))?.let {
                        VoteDropped(it.vote, VoteDropReason.SUPERSEDED)
                    }
                } else if (rejectedTransactionCache.containsKey(vote.blockHash)) {
                    VoteDropped(vote, VoteDropReason.TRANSACTION_DROPPED)
                } else {
                    logger.trace { "Buffered until election starts. $vote" }
                    voteBuffer.compute(vote.blockHash) { _, m ->
                        val map = m ?: HashMap()
                        map.compute(vote.publicKey) { _, v ->
                            if (v == null || vote.timestamp > v.timestamp) {
                                vote
                            } else {
                                v
                            }
                        }
                        map
                    }
                    null
                }
            }
        if (dropped != null) {
            logger.trace { "Dropped vote due to ${dropped.reason}. ${dropped.vote}" }
            eventPublisher.publish(dropped)
        }
    }

    @Scheduled(fixedRateString = "\${atto.vote.prioritization.frequency}")
    suspend fun process() {
        do {
            val transactionVote = mutex.withLock { queue.poll() }
            transactionVote?.let {
                eventPublisher.publish(VoteValidated(it.transaction, it.vote))
            }
        } while (transactionVote != null)
    }

    private fun validate(vote: Vote): VoteRejectionReason? {
        if (vote.weight == AttoAmount.MIN) {
            return VoteRejectionReason.INVALID_VOTING_WEIGHT
        }

        return null
    }

    override fun clear() {
        queue.clear()
        activeElections.clear()
        voteBuffer.clear()
        rejectedTransactionCache.clear()
        duplicateDetector.clear()
    }
}
