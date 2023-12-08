package atto.node.vote.priotization

import atto.node.CacheSupport
import atto.node.DuplicateDetector
import atto.node.EventPublisher
import atto.node.election.ElectionExpired
import atto.node.election.ElectionStarted
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionRejected
import atto.node.transaction.TransactionSaved
import atto.node.vote.*
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSignature
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class VotePrioritizer(
    properties: VotePrioritizationProperties,
    private val eventPublisher: EventPublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private lateinit var job: Job

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val queue = VoteQueue(properties.groupMaxSize!!)

    private val activeElections = ConcurrentHashMap<AttoHash, Transaction>()

    private val duplicateDetector = DuplicateDetector<AttoSignature>()

    private val rejectedTransactionCache = Caffeine.newBuilder()
        .maximumSize(10_000)
        .build<AttoHash, AttoHash>()
        .asMap()

    private val voteBuffer: MutableMap<AttoHash, MutableMap<AttoPublicKey, Vote>> = Caffeine.newBuilder()
        .maximumWeight(10_000)
        .weigher { _: AttoHash, v: MutableMap<AttoPublicKey, Vote> -> v.size }
        .evictionListener { _: AttoHash?, votes: MutableMap<AttoPublicKey, Vote>?, _ ->
            votes?.values?.forEach {
                eventPublisher.publish(VoteDropped(it, VoteDropReason.NO_ELECTION))
            }
        }
        .build<AttoHash, MutableMap<AttoPublicKey, Vote>>()
        .asMap()

    @PreDestroy
    fun preDestroy() {
        singleDispatcher.cancel()
    }

    fun getQueueSize(): Int {
        return queue.getSize()
    }

    fun getBufferSize(): Int {
        return voteBuffer.size
    }

    @EventListener
    suspend fun process(event: ElectionStarted) {
        val transaction = event.transaction

        activeElections[transaction.hash] = transaction

        withContext(singleDispatcher) {
            val votes = voteBuffer.remove(transaction.hash)?.values ?: setOf()
            votes.forEach {
                logger.trace { "Unbuffered vote and ready to be prioritized. $it" }
                add(it)
            }
        }
    }

    @EventListener
    fun process(event: TransactionRejected) {
        val hash = event.transaction.hash
        rejectedTransactionCache[hash] = hash
        val votes = voteBuffer.remove(hash)
        votes?.values?.forEach {
            eventPublisher.publish(VoteDropped(it, VoteDropReason.TRANSACTION_DROPPED))
        }
    }

    @EventListener
    fun process(event: TransactionSaved) {
        activeElections.remove(event.transaction.hash)
    }

    @EventListener
    fun process(event: ElectionExpired) {
        activeElections.remove(event.transaction.hash)
    }

    @EventListener
    fun add(event: VoteReceived) {
        val vote = event.vote

        if (duplicateDetector.isDuplicate(vote.signature)) {
            logger.trace { "Ignored duplicated $vote" }
            return
        }
        val rejectionReason = validate(vote)
        if (rejectionReason != null) {
            eventPublisher.publish(VoteRejected(rejectionReason, vote))
            return
        }

        if (rejectedTransactionCache.contains(vote.hash)) {
            eventPublisher.publish(VoteDropped(vote, VoteDropReason.TRANSACTION_DROPPED))
            return
        }

        runBlocking {
            add(vote)
        }
    }

    private suspend fun add(vote: Vote) {
        val transaction = activeElections[vote.hash]
        if (transaction != null) {
            logger.trace { "Queued for prioritization. $vote" }

            val droppedVote = withContext(singleDispatcher) {
                queue.add(VoteQueue.TransactionVote(transaction, vote))
            }

            droppedVote?.let {
                logger.trace { "Dropped from queue. $droppedVote" }
                eventPublisher.publish(VoteDropped(droppedVote.vote, VoteDropReason.SUPERSEDED))
            }
        } else {
            logger.trace { "Buffered until election starts. $vote" }
            voteBuffer.compute(vote.hash) { _, m ->
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
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName(this.javaClass.simpleName)) {
            while (isActive) {
                val transactionVote = withContext(singleDispatcher) {
                    queue.poll()
                }

                if (transactionVote != null) {
                    val transaction = transactionVote.transaction
                    val vote = transactionVote.vote

                    eventPublisher.publish(VoteValidated(transaction, vote))
                } else {
                    delay(100)
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        job.cancel()
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
        duplicateDetector.clear()
    }
}