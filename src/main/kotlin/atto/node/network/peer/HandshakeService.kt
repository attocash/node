package atto.node.network.peer

import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.DirectNetworkMessage
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.node.network.NodeBanned
import atto.protocol.AttoNode
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

    private val handshakes = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .maximumSize(100_000)
        .evictionListener { _: URI?, handshake: PeerHandshake?, _ ->
            handshake?.let {
                val socketAddress = it.socketAddress
                val node = it.node
                if (socketAddress != null && node != null) {
                    val peer = Peer(socketAddress, node)
                    peer.let { eventPublisher.publish(PeerRemoved(peer)) }
                }
            }
        }
        .build<URI, PeerHandshake>()
        .asMap()

    @EventListener
    fun add(peerEvent: PeerConnected) {
        val peer = peerEvent.peer
        peers[peer.node.publicUri] = peer
        handshakes.remove(peer.node.publicUri)
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

        handshakes.computeIfAbsent(publicUri) {
            val handshakeChallenge = AttoHandshakeChallenge.create()

            logger.info { "Starting handshake with $publicUri" }

            messagePublisher.publish(DirectNetworkMessage(publicUri, handshakeChallenge))

            PeerHandshake(publicUri, handshakeChallenge)
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
        val socketAddress = message.socketAddress
        val answer = message.payload
        val node = answer.node

        handshakes.computeIfPresent(message.publicUri) { _, peerHandshake ->
            if (peerHandshake.answered(socketAddress, answer)) {
                val peer = Peer(message.socketAddress, node)

                eventPublisher.publishSync(PeerAuthorized(peer))

                messagePublisher.publish(DirectNetworkMessage(message.publicUri, AttoHandshakeAcceptance()))

                if (peerHandshake.isConnected()) {
                    eventPublisher.publishSync(PeerConnected(peer))
                    null
                } else {
                    peerHandshake
                }
            } else {
                val rejected = PeerRejected(
                    PeerRejectionReason.INVALID_HANDSHAKE_ANSWER,
                    Peer(message.socketAddress, node)
                )
                eventPublisher.publish(rejected)
                logger.warn { "Invalid handshake answer was received $answer" }
                null
            }
        }
    }

    @EventListener
    fun processAcceptance(message: InboundNetworkMessage<AttoHandshakeAcceptance>) {
        handshakes.computeIfPresent(message.publicUri) { _, peerHandshake ->

            peerHandshake.accepted(message.payload)

            if (peerHandshake.isConnected()) {
                val peer = Peer(peerHandshake.socketAddress!!, peerHandshake.node!!)
                eventPublisher.publish(PeerConnected(peer))
                null
            } else {
                peerHandshake
            }
        }
    }

    private fun isKnown(publicAddress: URI): Boolean {
        if (publicAddress == thisNode.publicUri) {
            return true
        }
        val socketAddress = InetSocketAddress(publicAddress.host, publicAddress.port);
        return handshakes.contains(publicAddress) ||
                peers.containsKey(publicAddress) ||
                bannedNodes.contains(socketAddress.address)
    }

    override fun clear() {
        handshakes.clear()
        peers.clear()
    }

    private data class PeerHandshake(
        val publicUri: URI,
        val challenge: AttoHandshakeChallenge
    ) {
        @Volatile
        var socketAddress: InetSocketAddress? = null
            private set

        @Volatile
        var node: AttoNode? = null
            private set

        @Volatile
        private var ready = false
            private set

        fun answered(socketAddress: InetSocketAddress, answer: AttoHandshakeAnswer): Boolean {
            val node = answer.node
            val publicKey = node.publicKey

            val hash = AttoHash.hash(64, node.publicKey.value, challenge.value)
            if (!answer.signature.isValid(publicKey, hash)) {
                return false
            }

            this.node = node
            this.socketAddress = socketAddress

            return true
        }

        fun accepted(acceptance: AttoHandshakeAcceptance) {
            ready = true
        }

        fun isConnected(): Boolean {
            return ready && node != null
        }
    }
}