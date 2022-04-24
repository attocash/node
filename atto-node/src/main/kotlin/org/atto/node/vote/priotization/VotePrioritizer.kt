package org.atto.node.vote.priotization

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.commons.AttoSignature
import org.atto.node.EventPublisher
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionConfirmed
import org.atto.node.transaction.TransactionObserved
import org.atto.node.transaction.TransactionStaled
import org.atto.node.vote.*
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Service
class VotePrioritizer(
    properties: VotePrioritizationProperties,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private lateinit var job: Job

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val queue = VoteQueue(properties.groupMaxSize!!)

    private val activeElections = HashMap<AttoHash, Transaction>()

    private val voteBuffer: MutableMap<AttoHash, MutableMap<AttoPublicKey, Vote>> = Caffeine.newBuilder()
        .maximumWeight(properties.cacheMaxSize!!.toLong())
        .weigher { _: AttoHash, v: MutableMap<AttoPublicKey, Vote> -> v.size }
        .build<AttoHash, MutableMap<AttoPublicKey, Vote>>()
        .asMap()

    private val signatureCache: MutableMap<AttoSignature, AttoSignature> = Caffeine.newBuilder()
        .maximumSize(properties.cacheMaxSize!!.toLong())
        .build<AttoSignature, AttoSignature>()
        .asMap()

    fun getQueueSize(): Int {
        return queue.getSize()
    }

    fun getBufferSize(): Int {
        return voteBuffer.size
    }

    @EventListener
    fun processObserved(event: TransactionObserved) = runBlocking(singleDispatcher) {
        val transaction = event.payload

        activeElections[transaction.hash] = transaction

        val votes = voteBuffer.remove(transaction.hash)?.values ?: setOf()

        votes.forEach {
            logger.trace { "Unbuffered $it" }
            add(it)
        }
    }

    @EventListener
    fun processStaled(event: TransactionStaled) = runBlocking(singleDispatcher) {
        activeElections.remove(event.payload.hash)
    }

    @EventListener
    fun processConfirmed(event: TransactionConfirmed) = runBlocking(singleDispatcher) {
        activeElections.remove(event.payload.hash)
    }

    @EventListener
    fun add(event: VoteReceived) {
        val vote = event.payload

        val currentSignature = signatureCache.putIfAbsent(vote.signature, vote.signature)
        if (currentSignature != null) {
            logger.trace { "Ignored duplicated $vote" }
            return
        }

        runBlocking {
            add(vote)
        }
    }

    private suspend fun add(vote: Vote) = withContext(singleDispatcher) {
        val transaction = activeElections[vote.hash]
        if (transaction != null) {
            val droppedVote = queue.add(vote)
            if (droppedVote != null) {
                eventPublisher.publish(VoteDropped(transaction, vote))
            }
            logger.trace { "Queued $vote" }
        } else {
            voteBuffer.compute(vote.hash) { _, v ->
                val map = v ?: HashMap()
                map[vote.publicKey] = vote
                map
            }
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName("vote-prioritizer")) {
            while (isActive) {
                val vote = withContext(singleDispatcher) {
                    val vote = queue.poll() ?: return@withContext null

                    val transaction = activeElections[vote.hash]!!

                    val rejectionReason = validate(vote)
                    if (rejectionReason != null) {
                        eventPublisher.publish(VoteRejected(rejectionReason, vote))
                    } else {
                        eventPublisher.publish(VoteValidated(transaction, vote))
                    }

                    return@withContext vote
                }

                if (vote == null) {
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
        if (vote.weight == AttoAmount.min) {
            return VoteRejectionReason.INVALID_VOTING_WEIGHT
        }

        return null
    }
}