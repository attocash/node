package atto.node.network

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import atto.node.CacheSupport
import atto.node.network.peer.Peer
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.lang.Integer.min
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


private val random = Random()
private fun <T> randomSublist(list: List<T>, size: Int): List<T> {
    val randomSequence = generateSequence {
        random.nextInt(list.size)
    }
    return randomSequence
        .distinct()
        .take(min(list.size, size))
        .map { list[it] }
        .toList()
}

@Component
class MessageBroadcaster(
    networkBroadcasterProperties: NetworkBroadcasterProperties,
    val publisher: NetworkMessagePublisher
) : CacheSupport {
    val peers = ConcurrentHashMap<InetSocketAddress, Peer>()
    val voters = ConcurrentHashMap<InetSocketAddress, Peer>()

    private val peersByStrategy: LoadingCache<BroadcastStrategy, Collection<Peer>> = Caffeine.newBuilder()
        .expireAfterAccess(networkBroadcasterProperties.cacheExpirationTimeInSeconds!!, TimeUnit.SECONDS)
        .build { strategy ->
            when (strategy) {
                BroadcastStrategy.EVERYONE -> {
                    peers.values
                }
//                BroadcastStrategy.MINORITY -> {
//                    if (peers.size < 20) {
//                        peers.values
//                    } else {
//                        randomSublist(peers.values.toList(), 20)
//                    }
//                }
                BroadcastStrategy.VOTERS -> {
                    voters.values.filter { it.node.isVoter() }
                }
//                BroadcastStrategy.HISTORICAL -> {
//                    val peers = voters.values.asSequence()
//                        .filter { it.node.isHistorical() }
//                        .toList()
//
//                    randomSublist(peers, 1)
//                }
            }
        }

    @EventListener
    @Async
    fun add(peerEvent: PeerAdded) {
        val peer = peerEvent.peer
        peers[peer.connectionSocketAddress] = peer
        if (peer.node.isVoter()) {
            voters[peer.connectionSocketAddress] = peer
        }
    }

    @EventListener
    @Async
    fun remove(peerEvent: PeerRemoved) {
        val peer = peerEvent.peer
        peers.remove(peer.connectionSocketAddress)
        if (peer.node.isVoter()) {
            voters.remove(peer.connectionSocketAddress)
        }
    }

    @EventListener
    @Async
    fun broadcast(event: BroadcastNetworkMessage<*>) {
        peersByStrategy[event.strategy]!!.asSequence()
            .map { it.connectionSocketAddress }
            .filter { !event.exceptions.contains(it) }
            .map { OutboundNetworkMessage(it, event.payload) }
            .forEach {
                publisher.publish(it)
            }
    }

    override fun clear() {
        peers.clear()
        voters.clear()
        peersByStrategy.invalidateAll()
    }


}