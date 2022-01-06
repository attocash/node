package org.atto.node.network.peer

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import org.atto.commons.AttoPrivateKey
import org.atto.commons.sign
import org.atto.node.CacheSupport
import org.atto.node.EventPublisher
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.node.network.OutboundNetworkMessage
import org.atto.protocol.Node
import org.atto.protocol.network.handshake.HandshakeAnswer
import org.atto.protocol.network.handshake.HandshakeChallenge
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class HandshakeService(
    val properties: PeerProperties,
    val thisNode: Node,
    val privateKey: AttoPrivateKey,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap<InetSocketAddress, Peer>()

    private val challenges: Cache<InetSocketAddress, HandshakeChallenge> = Caffeine.newBuilder()
        .expireAfterWrite(properties.expirationTimeInSeconds, TimeUnit.SECONDS)
        .build()

    @EventListener
    fun add(peerEvent: PeerAddedEvent) {
        peers[peerEvent.payload.connectionSocketAddress] = peerEvent.payload
        challenges.invalidate(peerEvent.payload.node.socketAddress)
    }

    @EventListener
    fun remove(peerEvent: PeerRemovedEvent) {
        peers.remove(peerEvent.payload.node.socketAddress)
    }

    @Scheduled(cron = "0 0/1 * * * *")
    fun startDefaultHandshake() {
        if (peers.count() > properties.defaultNodes.size) {
            return
        }

        properties.defaultNodes.asSequence()
            .map {
                val address = it.split(":")
                InetSocketAddress(
                    InetAddress.getByName(address[0]),
                    address[1].toInt()
                )
            }
            .forEach {
                startHandshake(it)
            }
    }

    fun startHandshake(socketAddress: InetSocketAddress) {
        if (isKnown(socketAddress)) {
            return
        }

        val handshakeChallenge = HandshakeChallenge.create()
        challenges.put(socketAddress, handshakeChallenge)

        messagePublisher.publish(OutboundNetworkMessage(socketAddress, this, handshakeChallenge))

        logger.info { "Started handshake with $socketAddress" }
    }


    @EventListener
    fun processChallengeMessage(message: InboundNetworkMessage<HandshakeChallenge>) {
        val handshakeAnswer = HandshakeAnswer(
            signature = privateKey.sign(message.payload.value),
            node = thisNode
        )

        startHandshake(message.socketAddress)

        messagePublisher.publish(OutboundNetworkMessage(message.socketAddress, this, handshakeAnswer))
    }

    @EventListener
    fun processAnswer(message: InboundNetworkMessage<HandshakeAnswer>) {
        val answer = message.payload
        val node = answer.node

        val challenge = challenges.getIfPresent(message.socketAddress)
        if (challenge == null) {
            logger.warn { "Not requested handshake answer (or too old) was received from $node" }
            return
        }

        val publicKey = node.publicKey

        if (!answer.signature.isValid(publicKey, challenge.value)) {
            logger.warn { "Invalid handshake answer was received $answer" }
            return
        }

        val peer = Peer(message.socketAddress, node)
        eventPublisher.publish(PeerAddedEvent(peer))
    }

    private fun isKnown(socketAddress: InetSocketAddress): Boolean {
        if (socketAddress == thisNode.socketAddress) {
            return true
        }
        return challenges.getIfPresent(socketAddress) != null || peers.containsKey(socketAddress)
    }

    override fun clear() {
        challenges.invalidateAll()
        peers.clear()
    }
}