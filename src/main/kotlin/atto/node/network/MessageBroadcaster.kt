package atto.node.network

import atto.node.CacheSupport
import atto.node.network.peer.Peer
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


private val random = Random()

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
            when (strategy!!) {
                BroadcastStrategy.EVERYONE -> {
                    peers.values
                }

                BroadcastStrategy.VOTERS -> {
                    voters.values.filter { it.node.isVoter() }
                }
            }
        }

    @EventListener
    fun add(peerEvent: PeerAdded) {
        val peer = peerEvent.peer
        peers[peer.connectionSocketAddress] = peer
        if (peer.node.isVoter()) {
            voters[peer.connectionSocketAddress] = peer
        }
    }

    @EventListener
    fun remove(peerEvent: PeerRemoved) {
        val peer = peerEvent.peer
        peers.remove(peer.connectionSocketAddress)
        if (peer.node.isVoter()) {
            voters.remove(peer.connectionSocketAddress)
        }
    }

    @EventListener
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