package org.atto.node.network.guardian

import org.atto.node.CacheSupport
import org.atto.node.EventPublisher
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NodeBanned
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap

@Service
class Guardian(private val eventPublisher: EventPublisher) : CacheSupport {
    private val toleranceMultiplier = 1_000


    private val statisticsMap = ConcurrentHashMap<InetAddress, Long>()
    private var snapshot = HashMap<InetAddress, Long>()

    @EventListener
    @Async
    fun add(message: InboundNetworkMessage<*>) {
        val address = message.socketAddress.address
        statisticsMap.compute(address) { k, v ->
            (v ?: 0) + 1
        }
    }

    @Scheduled(cron = "0/5 * * * * *")
    fun guard() {
        val newSnapshot = HashMap(statisticsMap)

        val difference = getDifference(newSnapshot)
        val median = median(difference.keys)
        val maliciousActors = difference.tailMap(median * toleranceMultiplier)

        maliciousActors.values.forEach {
            eventPublisher.publish(NodeBanned(it))
        }
    }

    private fun getDifference(newSnapshot: Map<InetAddress, Long>): TreeMap<Long, InetAddress> {
        val treeMap = TreeMap<Long, InetAddress>()
        return newSnapshot.asSequence()
            .map {
                val hits = it.value - (snapshot[it.key] ?: 0)
                Pair(hits, it.key)
            }
            .toMap(treeMap)
    }


    private fun median(hits: Collection<Long>): Long {
        val list = hits.toList()
        val middle = hits.size / 2
        if (middle % 2 == 1) {
            return list[middle]
        }
        return (list[middle - 1] + list[middle]) / 2
    }

    override fun clear() {
        statisticsMap.clear()
    }
}