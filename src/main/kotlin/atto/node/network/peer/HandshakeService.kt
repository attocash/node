package atto.node.network.peer

import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.DirectNetworkMessage
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.node.network.NodeBanned
import atto.protocol.network.handshake.AttoHandshakeAcceptance
import atto.protocol.network.handshake.AttoHandshakeAnswer
import atto.protocol.network.handshake.AttoHandshakeChallenge
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.sign
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class HandshakeService(
    val properties: PeerProperties,
    val thisNode: atto.protocol.AttoNode,
    val privateKey: AttoPrivateKey,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap<URI, Peer>()
    private val bannedNodes = ConcurrentHashMap.newKeySet<InetAddress>()

    private val challenges = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .maximumSize(100_000)
        .build<URI, AttoHandshakeChallenge>()
        .asMap()

    private val authorizedNodes = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .maximumSize(100_000)
        .evictionListener { _: URI?, peer: Peer?, _ ->
            peer?.let { eventPublisher.publish(PeerRemoved(it)) }
        }
        .build<URI, Peer>()
        .asMap()

    @EventListener
    fun add(peerEvent: PeerConnected) {
        val peer = peerEvent.peer
        peers[peer.node.publicUri] = peer
        challenges.remove(peer.node.publicUri)
    }

    @EventListener
    fun remove(peerEvent: PeerRemoved) {
        val peer = peerEvent.peer
        peers.remove(peer.node.publicUri)
    }

    @EventListener
    fun ban(event: NodeBanned) {
        bannedNodes.add(event.address)
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    fun startDefaultHandshake() {
        if (peers.size > properties.defaultNodes.size) {
            return
        }

        properties.defaultNodes.asSequence()
            .map { URI(it) }
            .forEach {
                startHandshake(it)
            }
    }

    fun startHandshake(publicUri: URI) {
        if (isKnown(publicUri)) {
            logger.trace { "Ignoring handshake with $publicUri. This node is already known" }
            return
        }

        challenges.computeIfAbsent(publicUri) {
            val handshakeChallenge = AttoHandshakeChallenge.create()

            logger.info { "Starting handshake with $publicUri" }
            messagePublisher.publish(DirectNetworkMessage(publicUri, handshakeChallenge))

            handshakeChallenge
        }
    }


    @EventListener
    fun processChallenge(message: InboundNetworkMessage<AttoHandshakeChallenge>) {
        val hash = AttoHash.hash(64, thisNode.publicKey.value, message.payload.value)
        val handshakeAnswer = AttoHandshakeAnswer(
            signature = privateKey.sign(hash),
            node = thisNode
        )

        startHandshake(message.publicUri)

        messagePublisher.publish(DirectNetworkMessage(message.publicUri, handshakeAnswer))
    }

    @EventListener
    fun processAnswer(message: InboundNetworkMessage<AttoHandshakeAnswer>) {
        val answer = message.payload
        val node = answer.node

        challenges.computeIfPresent(message.publicUri) { _, challenge ->
            val publicKey = node.publicKey

            val hash = AttoHash.hash(64, node.publicKey.value, challenge.value)
            if (!answer.signature.isValid(publicKey, hash)) {
                val rejected = PeerRejected(
                    PeerRejectionReason.INVALID_HANDSHAKE_ANSWER,
                    Peer(message.socketAddress, node)
                )
                eventPublisher.publish(rejected)
                logger.warn { "Invalid handshake answer was received $answer" }
            } else {
                val peer = Peer(message.socketAddress, node)
                authorizedNodes.putIfAbsent(message.publicUri, peer)
                eventPublisher.publish(PeerAuthorized(peer))
                messagePublisher.publish(DirectNetworkMessage(message.publicUri, AttoHandshakeAcceptance()))
            }
            null
        }
    }

    @EventListener
    fun processAcceptance(message: InboundNetworkMessage<AttoHandshakeAcceptance>) {
        authorizedNodes.computeIfPresent(message.publicUri) { _, peer ->
            eventPublisher.publish(PeerConnected(peer))
            null
        }
    }

    private fun isKnown(publicAddress: URI): Boolean {
        if (publicAddress == thisNode.publicUri) {
            return true
        }
        val socketAddress = InetSocketAddress(publicAddress.host, publicAddress.port);
        return challenges.contains(publicAddress) ||
                peers.containsKey(publicAddress) ||
                bannedNodes.contains(socketAddress.address)
    }

    override fun clear() {
        challenges.clear()
        peers.clear()
    }
}