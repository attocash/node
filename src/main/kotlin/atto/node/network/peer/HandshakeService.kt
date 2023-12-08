package atto.node.network.peer

import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.node.network.NodeBanned
import atto.node.network.OutboundNetworkMessage
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

    private val peers = ConcurrentHashMap<InetSocketAddress, Peer>()
    private val bannedNodes = ConcurrentHashMap.newKeySet<InetAddress>()

    private val challenges = Caffeine.newBuilder()
        .expireAfterWrite(5, TimeUnit.SECONDS)
        .build<InetSocketAddress, AttoHandshakeChallenge>()
        .asMap()

    @EventListener
    fun add(peerEvent: PeerAdded) {
        val peer = peerEvent.peer
        peers[peer.node.socketAddress] = peer
        peers[peer.connectionSocketAddress] = peer
        challenges.remove(peer.connectionSocketAddress)
    }

    @EventListener
    fun remove(peerEvent: PeerRemoved) {
        val peer = peerEvent.peer
        peers.remove(peer.node.socketAddress)
        peers.remove(peer.connectionSocketAddress)
    }

    @EventListener
    fun ban(event: NodeBanned) {
        bannedNodes.add(event.address)
    }

    @Scheduled(cron = "0 0/1 * * * *")
    fun startDefaultHandshake() {
        if (peers.size > properties.defaultNodes.size) {
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
            logger.debug { "Ignoring handshake with $socketAddress. This node is already known" }
            return
        }

        val handshakeChallenge = AttoHandshakeChallenge.create()
        challenges.put(socketAddress, handshakeChallenge)

        messagePublisher.publish(OutboundNetworkMessage(socketAddress, handshakeChallenge))

        logger.info { "Started handshake with $socketAddress" }
    }


    @EventListener
    fun processChallenge(message: InboundNetworkMessage<AttoHandshakeChallenge>) {
        val hash = AttoHash.hash(64, thisNode.toByteBuffer().toByteArray(), message.payload.value)
        val handshakeAnswer = AttoHandshakeAnswer(
            signature = privateKey.sign(hash),
            node = thisNode
        )

        startHandshake(message.socketAddress)

        messagePublisher.publish(OutboundNetworkMessage(message.socketAddress, handshakeAnswer))
    }

    @EventListener
    fun processAnswer(message: InboundNetworkMessage<AttoHandshakeAnswer>) {
        val answer = message.payload
        val node = answer.node

        val challenge = challenges[message.socketAddress]
        if (challenge == null) {
            val rejected = PeerRejected(PeerRejectionReason.UNKNOWN_HANDSHAKE, Peer(message.socketAddress, node))
            eventPublisher.publish(rejected)
            logger.warn { "Not requested handshake answer (or too old) was received from $node" }
            return
        }

        val publicKey = node.publicKey

        val hash = AttoHash.hash(64, node.toByteBuffer().toByteArray(), challenge.value)
        if (!answer.signature.isValid(
                publicKey,
                hash
            )
        ) {
            val rejected = PeerRejected(PeerRejectionReason.INVALID_HANDSHAKE_ANSWER, Peer(message.socketAddress, node))
            eventPublisher.publish(rejected)
            logger.warn { "Invalid handshake answer was received $answer" }
            return
        }

        eventPublisher.publish(PeerAdded(Peer(message.socketAddress, node)))
    }

    private fun isKnown(socketAddress: InetSocketAddress): Boolean {
        if (socketAddress == thisNode.socketAddress) {
            return true
        }
        return challenges.contains(socketAddress) ||
                peers.containsKey(socketAddress) ||
                bannedNodes.contains(socketAddress.address)
    }

    override fun clear() {
        challenges.clear()
        peers.clear()
    }
}