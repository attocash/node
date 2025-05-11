package cash.atto.node.network.guardian

import cash.atto.commons.AttoPublicKey
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NodeBanned
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.node.vote.weight.VoteWeighter
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class Guardian(
    private val voteWeighter: VoteWeighter,
    private val eventPublisher: EventPublisher,
    private val guardianProperties: GuardianProperties,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val statisticsMap = ConcurrentHashMap<InetSocketAddress, ULong>()
    private val voterMap = ConcurrentHashMap<InetSocketAddress, AttoPublicKey>()

    /**
     * Allow extra requests when messages were actually requested
     */
    private val expectedResponseCountMap = ConcurrentHashMap<URI, ULong>()

    @Volatile
    private var snapshot: Map<InetSocketAddress, ULong> = mapOf()


    @EventListener
    fun count(message: DirectNetworkMessage<*>) {
        val uri = message.publicUri
        expectedResponseCountMap.compute(uri) { _, v ->
            val count = (v ?: 0UL) + message.expectedResponseCount
            if (count == 0UL) {
                return@compute null
            }
            return@compute count
        }
    }

    @EventListener
    fun count(message: InboundNetworkMessage<*>) {
        val socketAddress = message.socketAddress
        val expectedCount = expectedResponseCountMap.compute(message.publicUri) { _, v ->
            val count = v ?: 0UL
            if (count == 0UL) {
                return@compute null
            }
            return@compute count - 1UL
        }
        if (expectedCount != null) {
            return
        }
        statisticsMap.compute(socketAddress) { _, v ->
            (v ?: 0UL) + 1UL
        }
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

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    fun guard() {
        val newSnapshot = statisticsMap.toMap()

        val differenceMap = calculateDifference(newSnapshot, snapshot)
        val median = median(extractVoters(differenceMap).values)

        if (median < guardianProperties.minimalMedian) {
            return
        }

        val mergedDifferenceMap =
            differenceMap
                .entries
                .groupBy({ it.key.address }, { it.value })
                .mapValues { it.value.sum() }

        val maliciousActors =
            mergedDifferenceMap
                .entries
                .associateBy({ it.value }, { it.key })
                .toSortedMap()
                .tailMap(median * guardianProperties.toleranceMultiplier)

        maliciousActors.forEach {
            logger.info { "Banning ${it.value}. Received ${it.key} requests while median of voters is $median per second" }
            eventPublisher.publish(NodeBanned(it.value))
        }

        snapshot = newSnapshot
    }

    private fun calculateDifference(
        newSnapshot: Map<InetSocketAddress, ULong>,
        oldSnapshot: Map<InetSocketAddress, ULong>,
    ): HashMap<InetSocketAddress, ULong> {
        val treeMap = HashMap<InetSocketAddress, ULong>()
        return newSnapshot
            .asSequence()
            .map {
                val hits = it.value - (oldSnapshot[it.key] ?: 0U)
                Pair(it.key, hits)
            }.toMap(treeMap)
    }

    private fun extractVoters(snapshot: Map<InetSocketAddress, ULong>): Map<InetSocketAddress, ULong> {
        return snapshot.filter {
            val publicKey = voterMap[it.key] ?: return@filter false
            return@filter voteWeighter.isAboveMinimalRebroadcastWeight(publicKey)
        }
    }

    private fun median(hits: Collection<ULong>): ULong {
        if (hits.isEmpty()) {
            return ULong.MAX_VALUE
        }
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

    override fun clear() {
        statisticsMap.clear()
        snapshot = mapOf()
        voterMap.clear()
    }
}
