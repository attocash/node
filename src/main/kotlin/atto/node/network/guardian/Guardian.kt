package atto.node.network.guardian

import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.network.NodeBanned
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import atto.node.vote.weight.VoteWeighter
import cash.atto.commons.AttoPublicKey
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap

@Service
class Guardian(private val voteWeighter: VoteWeighter, private val eventPublisher: EventPublisher) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val toleranceMultiplier = 10U
    }


    private val statisticsMap = ConcurrentHashMap<InetSocketAddress, ULong>()
    private val voterMap = ConcurrentHashMap<InetSocketAddress, AttoPublicKey>()

    @Volatile
    private var snapshot: Map<InetSocketAddress, ULong> = mapOf()

    @EventListener
    fun count(message: InboundNetworkMessage<*>) {
        val socketAddress = message.socketAddress
        statisticsMap.compute(socketAddress) { _, v ->
            (v ?: 0UL) + 1UL
        }
    }

    @EventListener
    fun add(peerEvent: PeerAdded) {
        val peer = peerEvent.peer

        if (peer.node.isNotVoter()) {
            return;
        }

        voterMap[peer.connectionSocketAddress] = peer.node.publicKey
    }

    @EventListener
    fun remove(peerEvent: PeerRemoved) {
        val peer = peerEvent.peer
        voterMap.remove(peer.connectionSocketAddress)
    }

    @Scheduled(cron = "0/1 * * * * *")
    fun guard() {
        val newSnapshot = statisticsMap.toMap()

        val differenceMap = calculateDifference(newSnapshot, snapshot)
        val median = median(extractVoters(differenceMap).values)


        val mergedDifferenceMap = differenceMap.entries
            .groupBy({ it.key.address }, { it.value })
            .mapValues { it.value.sum() }

        val maliciousActors = mergedDifferenceMap.entries
            .associateBy({ it.value }, { it.key })
            .toSortedMap()
            .tailMap(median * toleranceMultiplier)

        maliciousActors.forEach {
            logger.info { "Banning ${it.value}. Received ${it.key} requests while median of voters is $median per second" }
            eventPublisher.publish(NodeBanned(it.value))
        }

        snapshot = newSnapshot
    }

    private fun calculateDifference(
        newSnapshot: Map<InetSocketAddress, ULong>,
        oldSnapshot: Map<InetSocketAddress, ULong>
    ): HashMap<InetSocketAddress, ULong> {
        val treeMap = HashMap<InetSocketAddress, ULong>()
        return newSnapshot.asSequence()
            .map {
                val hits = it.value - (oldSnapshot[it.key] ?: 0U)
                Pair(it.key, hits)
            }
            .toMap(treeMap)
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

    fun getSnapshot(): Map<InetSocketAddress, ULong> {
        return snapshot.toMap()
    }

    fun getVoters(): Map<InetSocketAddress, AttoPublicKey> {
        return voterMap.toMap()
    }

    override fun clear() {
        statisticsMap.clear()
        snapshot = mapOf()
        voterMap.clear()

    }
}