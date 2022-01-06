package org.atto.node.vote.validator

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.*
import mu.KotlinLogging
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.commons.AttoSignature
import org.atto.node.CacheSupport
import org.atto.node.EventPublisher
import org.atto.node.network.BroadcastNetworkMessage
import org.atto.node.network.BroadcastStrategy
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.transaction.TransactionConfirmed
import org.atto.node.transaction.TransactionObserved
import org.atto.node.transaction.TransactionStaled
import org.atto.node.vote.HashVoteQueue
import org.atto.node.vote.HashVoteRejected
import org.atto.node.vote.HashVoteValidated
import org.atto.node.vote.VoteRejectionReasons
import org.atto.node.vote.weight.VoteWeightService
import org.atto.protocol.Node
import org.atto.protocol.vote.HashVote
import org.atto.protocol.vote.VotePush
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.annotation.PostConstruct
import javax.annotation.PreDestroy

@Component
class VoteValidator(
    properties: VoteValidatorProperties,
    private val scope: CoroutineScope,
    private val voteWeightService: VoteWeightService,
    private val thisNode: Node,
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    @OptIn(ExperimentalCoroutinesApi::class)
    private val singleDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val queue = HashVoteQueue(properties.groupMaxSize!!)

    private lateinit var job: Job

    private val activeElections = ConcurrentHashMap.newKeySet<AttoHash>()

    private val socketAddresses: Cache<AttoSignature, InetSocketAddress> = Caffeine.newBuilder()
        .expireAfterAccess(properties.cacheExpirationTimeInSeconds!!, TimeUnit.SECONDS)
        .maximumSize(properties.cacheMaxSize!!)
        .build()

    private val voteBuffer: Cache<AttoHash, HashMap<AttoPublicKey, HashVote>> = Caffeine.newBuilder()
        .expireAfterWrite(properties.cacheExpirationTimeInSeconds!!, TimeUnit.SECONDS)
        .maximumSize(properties.cacheMaxSize!!)
        .build()

    @EventListener
    fun process(event: TransactionObserved) {
        val hash = event.transaction.hash
        activeElections.add(hash)
        scope.launch {
            withContext(singleDispatcher) {
                val votes = voteBuffer.asMap().remove(hash)
                if (votes != null) {
                    logger.trace { "Unbuffered ${votes.size} votes for $hash" }
                    votes.values.forEach { sendEvent(it) }
                }
            }
        }
    }

    @EventListener
    fun process(event: TransactionStaled) {
        activeElections.remove(event.transaction.hash)
    }

    @EventListener
    fun process(event: TransactionConfirmed) {
        activeElections.remove(event.transaction.hash)
    }

    @EventListener
    fun add(message: InboundNetworkMessage<VotePush>) {
        val hashVote = message.payload.hashVote

        val previousSocketAddress = socketAddresses.asMap().putIfAbsent(hashVote.vote.signature, message.socketAddress)
        if (previousSocketAddress != null) {
            logger.trace { "Ignored duplicated $hashVote" }
            return
        }

        scope.launch {
            withContext(singleDispatcher) {
                add(hashVote)
            }
        }
    }

    suspend fun add(hashVote: HashVote) {
        queue.add(voteWeightService.get(hashVote.vote.publicKey), hashVote)
        logger.trace { "Queued $hashVote" }
    }


    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName("vote-validator")) {
            while (isActive) {
                val hashVote = withContext(singleDispatcher) {
                    val hashVote = queue.poll()
                    if (hashVote != null) {
                        process(hashVote)
                    }
                    hashVote
                }
                if (hashVote == null) {
                    delay(100)
                }
            }
        }
    }

    private suspend fun process(hashVote: HashVote) {
        val rejectionReason = validate(hashVote)
        if (rejectionReason != null) {
            val socketAddress = socketAddresses.getIfPresent(hashVote.vote.signature)
            val event = HashVoteRejected(socketAddress, rejectionReason, hashVote)
            logger.trace { "$event" }
            eventPublisher.publish(event)
        } else {
            if (activeElections.contains(hashVote.hash)) {
                sendEvent(hashVote)
            } else if (voteWeightService.isAboveMinimalRebroadcastWeight(hashVote.vote.publicKey)) {
                withContext(singleDispatcher) {
                    voteBuffer.asMap().compute(hashVote.hash) { _, existingVoteMap ->
                        val voteMap = existingVoteMap ?: HashMap()
                        voteMap.compute(hashVote.vote.publicKey) { _, existingHashVote ->
                            if (existingHashVote == null || existingHashVote.vote.timestamp < hashVote.vote.timestamp) {
                                hashVote
                            } else {
                                existingHashVote
                            }
                        }
                        voteMap
                    }
                }
                logger.trace { "Buffered $hashVote" }
            } else {
                logger.trace { "Ignored vote due to low weight $hashVote" }
            }
            broadcast(hashVote)
        }
    }

    private fun sendEvent(hashVote: HashVote) {
        val event = HashVoteValidated(hashVote)
        logger.trace { "$event" }
        eventPublisher.publish(event)
    }

    private fun validate(hashVote: HashVote): VoteRejectionReasons? {
        if (!hashVote.isValid()) {
            return VoteRejectionReasons.INVALID_VOTE
        }

        if (voteWeightService.get(hashVote.vote.publicKey) == 0UL) {
            return VoteRejectionReasons.INVALID_VOTING_WEIGHT
        }

        return null
    }

    private fun broadcast(hashVote: HashVote) {
        if (!thisNode.isVoter()) {
            return
        }

        if (!voteWeightService.isAboveMinimalRebroadcastWeight(hashVote.vote.publicKey)) {
            return
        }

        if (!voteWeightService.isAboveMinimalRebroadcastWeight(thisNode.publicKey)) {
            return
        }

        val broadcastStrategy = if (hashVote.vote.isFinal()) {
            BroadcastStrategy.EVERYONE
        } else {
            BroadcastStrategy.VOTERS
        }

        val socketAddress = socketAddresses.getIfPresent(hashVote.vote.signature)
        val exceptions = if (socketAddress != null) setOf(socketAddress) else setOf()
        val hashVotePush = VotePush(hashVote)
        val broadcast = BroadcastNetworkMessage(broadcastStrategy, exceptions, this, hashVotePush)
        messagePublisher.publish(broadcast)
    }

    @PreDestroy
    fun stop() {
        job.cancel()
    }

    override fun clear() {
        socketAddresses.invalidateAll()
        voteBuffer.invalidateAll()
    }
}