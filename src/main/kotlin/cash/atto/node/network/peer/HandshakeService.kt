package cash.atto.node.network.peer

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.sign
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeBanned
import cash.atto.protocol.AttoHandshakeAcceptance
import cash.atto.protocol.AttoHandshakeAnswer
import cash.atto.protocol.AttoHandshakeChallenge
import cash.atto.protocol.AttoNode
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
    val thisNode: AttoNode,
    val privateKey: AttoPrivateKey,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap<URI, Peer>()
    private val bannedNodes = ConcurrentHashMap.newKeySet<InetAddress>()

    private val handshakes =
        Caffeine
            .newBuilder()
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
            }.build<URI, PeerHandshake>()
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

        properties
            .defaultNodes
            .asSequence()
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
        val handshakeAnswer =
            AttoHandshakeAnswer(
                signature = privateKey.sign(hash),
                node = thisNode,
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
                val rejected =
                    PeerRejected(
                        PeerRejectionReason.INVALID_HANDSHAKE_ANSWER,
                        Peer(message.socketAddress, node),
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
        if (publicAddress.host == null) {
            return false
        }

        val port =
            if (publicAddress.port > 0) {
                publicAddress.port
            } else if (publicAddress.host.startsWith("wss://")) {
                443
            } else {
                80
            }

        val socketAddress = InetSocketAddress(publicAddress.host, port)
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
        val challenge: AttoHandshakeChallenge,
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

        fun answered(
            socketAddress: InetSocketAddress,
            answer: AttoHandshakeAnswer,
        ): Boolean {
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

        fun isConnected(): Boolean = ready && node != null
    }
}
