package cash.atto.node.vote

import cash.atto.commons.AttoSignature
import cash.atto.node.CacheSupport
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoVotePush
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.URI
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * This rebroadcaster aims to reduce data usage creating a list of nodes that already saw these transactions while
 * it waits for the internal validations.
 *
 * Once the account change is validated the transaction that triggered this change is added to the buffer and later
 * rebroadcasted.
 *
 */
@Service
class VoteRebroadcaster(
    private val thisNode: AttoNode,
    private val voteWeighter: VoteWeighter,
    private val messagePublisher: NetworkMessagePublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val holderMap = ConcurrentHashMap<AttoSignature, VoteHolder>()
    private val voteQueue = PriorityQueue<VoteHolder>()

    @PreDestroy
    fun stop() {
        singleDispatcher.cancel()
    }

    @EventListener
    fun process(event: VoteReceived) {
        val vote = event.vote

        holderMap.compute(vote.signature) { _, v ->
            val holder = v ?: VoteHolder(vote)
            holder.add(event.publicUri)
            holder
        }

        logger.trace { "Started monitoring vote to rebroadcast. $vote" }
    }

    @EventListener
    suspend fun process(event: VoteValidated) {
        val holder = holderMap[event.vote.signature]
        /**
         * Holder will be null for votes casted by this node.
         * They are considered valid from the start and broadcasted directly
         */
        if (holder != null && isRebroadcaster()) {
            withContext(singleDispatcher) {
                voteQueue.add(holder)
                logger.trace { "Vote queued for rebroadcast. ${event.vote}" }
            }
        }
    }

    private fun isRebroadcaster(): Boolean {
        if (thisNode.isNotVoter()) {
            return false
        }
        return voteWeighter.isAboveMinimalRebroadcastWeight(thisNode.publicKey)
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

    @Scheduled(fixedRate = 100)
    suspend fun process() {
        withContext(singleDispatcher) {
            do {
                val voteHolder = voteQueue.poll()
                voteHolder?.let {
                    val vote = it.vote
                    val votePush = AttoVotePush(vote.toAtto())
                    val exceptions = it.publicUris

                    val message =
                        BroadcastNetworkMessage(
                            BroadcastStrategy.EVERYONE,
                            exceptions,
                            votePush,
                        )

                    logger.trace { "Vote dequeued and it will be rebroadcasted. $vote" }
                    messagePublisher.publish(message)
                }
            } while (voteHolder != null)
        }
    }

    class VoteHolder(
        val vote: Vote,
    ) : Comparable<VoteHolder> {
        val publicUris = HashSet<URI>()

        fun add(publicUri: URI) {
            publicUris.add(publicUri)
        }

        override fun compareTo(other: VoteHolder): Int =
            other
                .vote
                .weight
                .raw
                .compareTo(vote.weight.raw)
    }

    override fun clear() {
        holderMap.clear()
        voteQueue.clear()
    }
}
