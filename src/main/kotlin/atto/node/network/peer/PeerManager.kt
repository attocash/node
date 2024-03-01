package atto.node.network.peer

import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.*
import atto.protocol.network.peer.AttoKeepAlive
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.URI
import java.util.concurrent.TimeUnit

@Service
class PeerManager(
    properties: PeerProperties,
    val handshakeService: HandshakeService,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
) : CacheSupport {
    private val peers = Caffeine.newBuilder()
        .expireAfterWrite(properties.expirationTimeInSeconds, TimeUnit.SECONDS)
        .removalListener { _: URI?, peer: Peer?, _ ->
            peer?.let { eventPublisher.publish(PeerRemoved(it)) }
        }.build<URI, Peer>()
        .asMap()

    @EventListener
    fun process(peerEvent: PeerConnected) {
        val peer = peerEvent.peer
        peers[peer.node.publicUri] = peer
    }

    @EventListener
    fun process(nodeEvent: NodeDisconnected) {
        peers.remove(nodeEvent.publicUri)
    }

    @EventListener
    fun process(message: InboundNetworkMessage<AttoKeepAlive>) {
        peers.compute(message.publicUri) { _, value -> value } // refresh cache

        message.payload.neighbour?.let {
            handshakeService.startHandshake(it)
        }
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    fun sendKeepAlive() {
        val peer = peers.values.random()
        val keepAlive = AttoKeepAlive(peer.node.publicUri)
        messagePublisher.publish(BroadcastNetworkMessage(BroadcastStrategy.EVERYONE, setOf(), keepAlive))
    }

    fun getPeers(): List<Peer> {
        return peers.values.toList()
    }

    private fun peerSample(): URI {
        return peers.values.asSequence()
            .map { it.node.publicUri }
            .shuffled()
            .first()
    }

    override fun clear() {
        peers.clear()
    }
}