package org.atto.node.network

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import org.atto.node.CacheSupport
import org.atto.node.network.peer.Peer
import org.atto.node.network.peer.PeerAddedEvent
import org.atto.node.network.peer.PeerRemovedEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Component
class MessageBroadcaster(
    networkBroadcasterProperties: NetworkBroadcasterProperties,
    val publisher: NetworkMessagePublisher
) : CacheSupport {
    val peers = ConcurrentHashMap<InetSocketAddress, Peer>()
    val voters = ConcurrentHashMap<InetSocketAddress, Peer>()

    private val peersByStrategy: LoadingCache<BroadcastStrategy, Collection<Peer>> = Caffeine.newBuilder()
        .expireAfterAccess(networkBroadcasterProperties.cacheExpirationTimeInSeconds!!, TimeUnit.SECONDS)
        .build {
            when (it!!) {
                BroadcastStrategy.EVERYONE -> {
                    peers.values
                }
                BroadcastStrategy.VOTERS -> {
                    voters.values
                }
            }
        }

    @EventListener
    @Async
    fun add(peerEvent: PeerAddedEvent) {
        val peer = peerEvent.peer
        peers[peer.connectionSocketAddress] = peer
        if (peer.node.isVoter()) {
            voters[peer.connectionSocketAddress] = peer
        }
    }

    @EventListener
    @Async
    fun remove(peerEvent: PeerRemovedEvent) {
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