package org.atto.node.vote

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.atto.commons.AttoSignature
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.NetworkMessagePublisher
import org.atto.protocol.vote.AttoVotePush
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.util.*

/**
 * This rebroadcaster aims to reduce data usage creating a list of nodes that already saw these transactions while
 * it waits for the internal validations.
 *
 * Once the account change is validated the transaction that triggered this change is added to the buffer and later
 * rebroadcasted.
 *
 */
@Service
class VoteRebroadcaster(private val messagePublisher: NetworkMessagePublisher) {
    private val logger = KotlinLogging.logger {}

    private lateinit var job: Job

    private val weightComparator: Comparator<VoteHolder> = Comparator.comparing { it.vote.weight }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)

    private val holderMap = HashMap<AttoSignature, VoteHolder>()
    private val voteQueue = PriorityQueue(weightComparator)

    @EventListener
    fun process(event: VoteReceived) = runBlocking(singleDispatcher) {
        val vote = event.payload

        holderMap.compute(vote.signature) { _, v ->
            val holder = v ?: VoteHolder(vote)
            holder.add(event.socketAddress)
            holder
        }

        logger.trace { "Started observing $vote to rebroadcast" }
    }

    @EventListener
    fun process(event: VoteValidated) = runBlocking(singleDispatcher) {
        val holder = holderMap.get(event.payload.signature)
        /**
         * Holder will be null for votes cast by this node. They are considered valid from the start.
         */
        if (holder != null) {
            voteQueue.add(holder)
        }
    }

    @EventListener
    fun process(event: VoteRejected) = runBlocking(singleDispatcher) {
        holderMap.remove(event.payload.signature)
        logger.trace { "Stopped observing ${event.payload.hash}. Transaction was rejected" }
    }

    @EventListener
    fun process(event: VoteDropped) = runBlocking(singleDispatcher) {
        holderMap.remove(event.payload.signature)
        logger.trace { "Stopped observing ${event.payload.hash}. Transaction was dropped" }
    }


    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName("vote-rebroadcaster")) {
            while (isActive) {
                val voteHolder = withContext(singleDispatcher) {
                    voteQueue.poll()
                }
                if (voteHolder != null) {
                    val votePush = AttoVotePush(voteHolder.vote.toAttoVote())
                    val exceptions = voteHolder.socketAddresses

                    val message = BroadcastNetworkMessage(
                        BroadcastStrategy.EVERYONE,
                        exceptions,
                        votePush,
                    )

                    messagePublisher.publish(message)
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

    private class VoteHolder(val vote: Vote) {
        val socketAddresses = HashSet<InetSocketAddress>()

        fun add(socketAddress: InetSocketAddress) {
            socketAddresses.add(socketAddress)
        }
    }

}