package atto.node.vote

import atto.node.AsynchronousQueueProcessor
import atto.node.CacheSupport
import atto.node.network.BroadcastNetworkMessage
import atto.node.network.BroadcastStrategy
import atto.node.network.NetworkMessagePublisher
import atto.node.vote.VoteRebroadcaster.VoteHolder
import atto.protocol.vote.AttoVotePush
import cash.atto.commons.AttoSignature
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * This rebroadcaster aims to reduce data usage creating a list of nodes that already saw these transactions while
 * it waits for the internal validations.
 *
 * Once the account change is validated the transaction that triggered this change is added to the buffer and later
 * rebroadcasted.
 *
 */
@Service
class VoteRebroadcaster(private val messagePublisher: NetworkMessagePublisher) :
    AsynchronousQueueProcessor<VoteHolder>(100.milliseconds), CacheSupport {
    private val logger = KotlinLogging.logger {}

    private lateinit var job: Job

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val holderMap = ConcurrentHashMap<AttoSignature, VoteHolder>()
    private val voteQueue = PriorityQueue<VoteHolder>()

    @PreDestroy
    fun stop() {
        singleDispatcher.cancel()
        job.cancel()
    }

    @EventListener
    fun process(event: VoteReceived) {
        val vote = event.vote

        holderMap.compute(vote.signature) { _, v ->
            val holder = v ?: VoteHolder(vote)
            holder.add(event.socketAddress)
            holder
        }

        logger.trace { "Started monitoring vote to rebroadcast. $vote" }
    }

    @EventListener
    suspend fun process(event: VoteValidated) {
        val holder = holderMap[event.vote.signature]
        /**
         * Holder will be null for votes casted by this node. They are considered valid from the start.
         */
        if (holder != null) {
            withContext(singleDispatcher) {
                voteQueue.add(holder)
                logger.trace { "Vote queued for rebroadcast. ${event.vote}" }
            }
        }
    }

    @EventListener
    fun process(event: VoteRejected) {
        holderMap.remove(event.vote.signature)
        logger.trace { "Stopped monitoring vote because it was rejected due to ${event.reason}. ${event.vote}" }
    }

    @EventListener
    fun process(event: VoteDropped) {
        holderMap.remove(event.vote.signature)
        logger.trace { "Stopped monitoring vote because event was dropped. ${event.vote}" }
    }

    override suspend fun poll(): VoteHolder? = withContext(singleDispatcher) {
        voteQueue.poll()
    }

    override suspend fun process(value: VoteHolder) {
        val vote = value.vote
        val votePush = AttoVotePush(vote.toAttoVote())
        val exceptions = value.socketAddresses

        val message = BroadcastNetworkMessage(
            BroadcastStrategy.EVERYONE,
            exceptions,
            votePush,
        )

        logger.trace { "Vote dequeued and it will be rebroadcasted. $vote" }
        messagePublisher.publish(message)
    }

    class VoteHolder(val vote: Vote) : Comparable<VoteHolder> {
        val socketAddresses = HashSet<InetSocketAddress>()

        fun add(socketAddress: InetSocketAddress) {
            socketAddresses.add(socketAddress)
        }

        override fun compareTo(other: VoteHolder): Int {
            return other.vote.weight.raw.compareTo(vote.weight.raw)
        }
    }

    override fun clear() {
        holderMap.clear()
        voteQueue.clear()
    }

}