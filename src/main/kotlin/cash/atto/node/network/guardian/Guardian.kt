package cash.atto.node.network.guardian

import cash.atto.commons.AttoPublicKey
import cash.atto.node.CacheSupport
import cash.atto.node.network.ConnectedPeers
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class Guardian(
    private val thisNode: AttoNode,
    private val voteWeighter: VoteWeighter,
    private val guardianProperties: GuardianProperties,
    private val peerBans: PeerBans,
    private val expectedResponses: ExpectedResponses,
    private val connectedPeers: ConnectedPeers,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    // Counting and window rotation share this lock so each hit belongs to exactly one window.
    private val statisticsLock = Any()
    private var statisticsMap = HashMap<InetSocketAddress, ULong>()
    private val connectionRequestCounters =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(100_000)
            .build<InetAddress, AtomicInteger>()
            .asMap()

    @Volatile
    private var snapshot: Map<InetSocketAddress, ULong> = mapOf()

    fun requestInboundConnection(address: InetAddress): InboundConnectionDecision {
        if (peerBans.isBanned(address)) {
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
    fun count(message: InboundNetworkMessage<*>) {
        if (message.publicUri == thisNode.publicUri) {
            logger.debug { "Received message from itself. Ignoring $message" }
            return
        }

        if (expectedResponses.consume(message.publicUri)) {
            return
        }
        count(message.socketAddress)
    }

    @Scheduled(fixedRate = 15, timeUnit = TimeUnit.SECONDS)
    @Synchronized
    fun guard() {
        expectedResponses.removeExpired()
        val windowCounts =
            synchronized(statisticsLock) {
                val completed = statisticsMap
                statisticsMap = HashMap()
                completed
            }
        snapshot = windowCounts

        val voterValues =
            getVoters()
                .entries
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
                peerBans.ban(address)
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

    fun getVoters(): Map<InetSocketAddress, AttoPublicKey> =
        connectedPeers
            .snapshot()
            .filter { it.node.isVoter() }
            .associate { it.socketAddress to it.node.publicKey }

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
        connectionRequestCounters.clear()
    }
}

sealed interface InboundConnectionDecision {
    data object Accepted : InboundConnectionDecision

    data object Banned : InboundConnectionDecision

    data object RateLimited : InboundConnectionDecision
}
