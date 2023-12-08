package atto.node.network.guardian

import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.network.NodeBanned
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import atto.node.vote.weight.VoteWeighter
import cash.atto.commons.AttoPublicKey
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Service
class Guardian(private val voteWeighter: VoteWeighter, private val eventPublisher: EventPublisher) : CacheSupport {
    private val toleranceMultiplier = 100UL


    private val statisticsMap = ConcurrentHashMap<InetAddress, ULong>()
    private var snapshot = HashMap<InetAddress, ULong>()
    private val voterMap = ConcurrentHashMap<InetAddress, MutableSet<AttoPublicKey>>()

    @EventListener
    fun count(message: InboundNetworkMessage<*>) {
        val address = message.socketAddress.address
        statisticsMap.compute(address) { k, v ->
            (v ?: 0UL) + 1UL
        }
    }

    @EventListener
    fun add(peerEvent: PeerAdded) {
        val peer = peerEvent.peer
        if (!peer.node.isVoter()) {
            return
        }
        voterMap.compute(peer.connectionSocketAddress.address) { k, v ->
            val publicKeys = v ?: HashSet()
            publicKeys.add(peer.node.publicKey)
            publicKeys
        }
    }

    @EventListener
    fun remove(peerEvent: PeerRemoved) {
        val peer = peerEvent.peer
        voterMap.compute(peer.connectionSocketAddress.address) { k, v ->
            val publicKeys = v ?: HashSet()
            publicKeys.remove(peer.node.publicKey)
            publicKeys.ifEmpty {
                null
            }
        }
    }

    @Scheduled(cron = "0/5 * * * * *")
    fun guard() {
        val newSnapshot = HashMap(statisticsMap)

        val difference = calculateDifference(newSnapshot)
        val median = median(extractVoters(difference).keys)
        val maliciousActors = difference.tailMap(median * toleranceMultiplier)

        maliciousActors.values.forEach {
            eventPublisher.publish(NodeBanned(it))
        }
    }

    private fun calculateDifference(newSnapshot: Map<InetAddress, ULong>): TreeMap<ULong, InetAddress> {
        val treeMap = TreeMap<ULong, InetAddress>()
        return newSnapshot.asSequence()
            .map {
                val hits = it.value - (snapshot[it.key] ?: 0U)
                Pair(hits, it.key)
            }
            .toMap(treeMap)
    }

    private fun extractVoters(snapshot: Map<ULong, InetAddress>): Map<ULong, InetAddress> {
        return snapshot.filter { isVoter(voterMap[it.value] ?: listOf()) }
    }

    private fun isVoter(attoPublicKeys: Collection<AttoPublicKey>): Boolean {
        val voteWeight = attoPublicKeys.asSequence()
            .map { voteWeighter.get(it) }
            .sumOf { it.raw }

        return voteWeighter.getMinimalRebroadcastWeight().raw <= voteWeight
    }


    private fun median(hits: Collection<ULong>): ULong {
        if (hits.isEmpty()) {
            return ULong.MAX_VALUE
        }
        val list = hits.sorted()
        val middle = hits.size / 2
        if (middle % 2 == 1) {
            return list[middle]
        }
        return (list[middle - 1] + list[middle]) / 2UL
    }

    fun getSnapshot(): Map<InetAddress, ULong> {
        return snapshot.toMap()
    }

    override fun clear() {
        statisticsMap.clear()
    }
}