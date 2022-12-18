package org.atto.node.network.peer

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import org.atto.node.CacheSupport
import org.atto.node.EventPublisher
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.network.OutboundNetworkMessage
import org.atto.protocol.network.peer.AttoKeepAlive
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

@Service
class PeerManager(
    properties: PeerProperties,
    val handshakeService: HandshakeService,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
) : CacheSupport {
    private val peers: Cache<InetSocketAddress, Peer> = Caffeine.newBuilder()
        .expireAfterWrite(properties.expirationTimeInSeconds, TimeUnit.SECONDS)
        .removalListener { _: InetSocketAddress?, peer: Peer?, _ ->
            peer?.let { eventPublisher.publish(PeerRemovedEvent(it)) }
        }.build()

    @EventListener
    fun process(peerEvent: PeerAddedEvent) {
        val peer = peerEvent.peer
        peers.put(peer.connectionSocketAddress, peer)
    }

    @EventListener
    fun process(message: InboundNetworkMessage<AttoKeepAlive>) {
        peers.asMap().compute(message.socketAddress) { _, value -> value } // refresh cache

        message.payload.neighbours.forEach { handshakeService.startHandshake(it) }
    }

    @Scheduled(cron = "0/10 * * * * *")
    fun sendKeepAlive() {
        val peerSample = peerSample()

        peers.asMap().keys.forEach {
            messagePublisher.publish(OutboundNetworkMessage(it, AttoKeepAlive(peerSample)))
        }
    }

    fun getPeers(): List<Peer> {
        return peers.asMap().values.toList()
    }

    private fun peerSample(): List<InetSocketAddress> {
        return peers.asMap().values.asSequence()
            .map { it.node.socketAddress }
            .shuffled()
            .take(8)
            .toList()
    }

    override fun clear() {
        peers.invalidateAll()
    }
}