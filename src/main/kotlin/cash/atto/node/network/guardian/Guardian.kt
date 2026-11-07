package cash.atto.node.network.guardian

import cash.atto.commons.AttoPublicKey
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NodeBanned
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.node.network.NodeUnbanned
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionStreamRequest
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class Guardian(
    private val thisNode: AttoNode,
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher,
    private val guardianProperties: GuardianProperties,
    private val clock: Clock,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    // Counting and window rotation share this lock so each hit belongs to exactly one window.
    private val statisticsLock = Any()
    private var statisticsMap = HashMap<InetSocketAddress, ULong>()
    private val voterMap = ConcurrentHashMap<InetSocketAddress, AttoPublicKey>()
    private val banned = ConcurrentHashMap.newKeySet<InetAddress>()
    private val connectionRequestCounters =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build<InetAddress, AtomicInteger>()
            .asMap()

    // Only outbound requests extend expiry; delayed delivery keeps the request's original deadline.
    private val expectedResponseCountMap = ConcurrentHashMap<URI, ExpectedResponses>()

    @Volatile
    private var snapshot: Map<InetSocketAddress, ULong> = mapOf()

    fun requestInboundConnection(address: InetAddress): InboundConnectionDecision {
        if (address in banned) {
            return InboundConnectionDecision.Banned
        }

        countInboundConnection(address)

        val limit = guardianProperties.connectionRequestLimitPerMinute
        if (limit <= 0) {
            return InboundConnectionDecision.RateLimited
        }

        val count = connectionRequestCounters.computeIfAbsent(address) { AtomicInteger() }.incrementAndGet()
        if (count > limit) {
            return InboundConnectionDecision.RateLimited
        }

        return InboundConnectionDecision.Accepted
    }

    @EventListener
    fun count(message: DirectNetworkMessage<*>) {
        if (message.expectedResponseCount == 0UL) {
            return
        }

        val now = clock.instant()
        val expiresAt = message.timestamp.plus(AttoTransactionStreamRequest.TIMEOUT)
        if (!now.isBefore(expiresAt)) {
            return
        }

        expectedResponseCountMap.compute(message.publicUri) { _, responses ->
            val remaining = responses?.takeIf { now.isBefore(it.expiresAt) }
            ExpectedResponses(
                count = (remaining?.count ?: 0UL) + message.expectedResponseCount,
                expiresAt = maxOf(remaining?.expiresAt ?: expiresAt, expiresAt),
            )
        }
    }

    @EventListener
    fun count(message: InboundNetworkMessage<*>) {
        if (message.publicUri == thisNode.publicUri) {
            logger.debug { "Received message from itself. Ignoring $message" }
            return
        }

        val now = clock.instant()
        var expected = false
        expectedResponseCountMap.computeIfPresent(message.publicUri) { _, responses ->
            if (!now.isBefore(responses.expiresAt)) {
                return@computeIfPresent null
            }
            expected = true
            if (responses.count == 1UL) {
                null
            } else {
                responses.copy(count = responses.count - 1UL)
            }
        }
        if (expected) {
            return
        }
        count(message.socketAddress)
    }

    @EventListener
    fun ban(event: NodeBanned) {
        banned += event.address
    }

    @EventListener
    fun unban(event: NodeUnbanned) {
        banned -= event.address
    }

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        if (nodeEvent.node.isNotVoter()) {
            return
        }

        voterMap[nodeEvent.connectionSocketAddress] = nodeEvent.node.publicKey
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        voterMap.remove(nodeEvent.connectionSocketAddress)
    }

    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.SECONDS)
    @Synchronized
    fun guard() {
        val now = clock.instant()
        expectedResponseCountMap.keys.forEach { uri ->
            expectedResponseCountMap.computeIfPresent(uri) { _, responses ->
                responses.takeIf { now.isBefore(it.expiresAt) }
            }
        }
        val windowCounts =
            synchronized(statisticsLock) {
                val completed = statisticsMap
                statisticsMap = HashMap()
                completed
            }
        snapshot = windowCounts

        val voterValues =
            voterMap.entries
                .filter { voteWeighter.isAboveMinimalRebroadcastWeight(it.value) }
                .map { windowCounts[it.key] ?: 0UL }
        if (voterValues.isEmpty()) {
            return
        }

        val median = median(voterValues)

        if (median < guardianProperties.minimalMedian) {
            return
        }

        val threshold = median * guardianProperties.toleranceMultiplier

        val mergedDifferenceMap =
            windowCounts
                .entries
                .groupBy({ it.key.address }, { it.value })
                .mapValues { it.value.sum() }

        mergedDifferenceMap
            .filter { it.value >= threshold }
            .forEach { (address, count) ->
                logger.info { "Banning $address. Received $count requests while median of voters is $median per second" }
                eventPublisher.publish(NodeBanned(address))
            }
    }

    private fun median(hits: Collection<ULong>): ULong {
        require(hits.isNotEmpty()) { "Cannot compute median of empty collection" }
        val sortedHits = hits.sorted()
        val middle = sortedHits.size / 2
        return if (sortedHits.size % 2 == 0) {
            (sortedHits[middle - 1] + sortedHits[middle]) / 2UL
        } else {
            sortedHits[middle]
        }
    }

    fun getSnapshot(): Map<InetSocketAddress, ULong> = snapshot.toMap()

    fun getVoters(): Map<InetSocketAddress, AttoPublicKey> = voterMap.toMap()

    internal fun expectedResponsePeerCount(): Int = expectedResponseCountMap.size

    private fun countInboundConnection(address: InetAddress) {
        count(InetSocketAddress(address, 0))
    }

    private fun count(socketAddress: InetSocketAddress) {
        synchronized(statisticsLock) {
            statisticsMap[socketAddress] = (statisticsMap[socketAddress] ?: 0UL) + 1UL
        }
    }

    override fun clear() {
        synchronized(statisticsLock) {
            statisticsMap.clear()
        }
        snapshot = mapOf()
        voterMap.clear()
        expectedResponseCountMap.clear()
        banned.clear()
        connectionRequestCounters.clear()
    }

    private data class ExpectedResponses(
        val count: ULong,
        val expiresAt: Instant,
    )
}

sealed interface InboundConnectionDecision {
    data object Accepted : InboundConnectionDecision

    data object Banned : InboundConnectionDecision

    data object RateLimited : InboundConnectionDecision
}
