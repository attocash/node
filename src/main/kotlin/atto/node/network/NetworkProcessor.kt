package atto.node.network


import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.codec.MessageCodecManager
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import atto.protocol.network.AttoMessage
import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.toHex
import cash.atto.commons.toUShort
import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.annotation.PreDestroy
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import reactor.netty.Connection
import reactor.netty.tcp.TcpClient
import reactor.netty.tcp.TcpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


@Service
class NetworkProcessor(
    val codecManager: MessageCodecManager,
    val eventPublisher: EventPublisher,
    val messagepublisher: NetworkMessagePublisher,
    environment: Environment,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val maxMessageSize = 1600
        const val maxOutboundBuffer = 10_000
        const val headerSize = 8
    }

    private val peers = ConcurrentHashMap.newKeySet<InetSocketAddress>()
    private val bannedNodes = ConcurrentHashMap.newKeySet<InetAddress>()

    private val outboundMap = ConcurrentHashMap<InetSocketAddress, Sinks.Many<OutboundNetworkMessage<*>>>()

    // Avoid event infinity loop when neighbour instantly disconnects
    private val disconnectionCache: Cache<InetAddress, InetAddress> = Caffeine.newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build()

    private val port = environment.getRequiredProperty("server.tcp.port", Int::class.java)

    private val server = TcpServer.create()
        .port(port)
        .doOnBind {
            logger.info { "TCP started on port $port" }
        }
        .doOnConnection {
            val socketAddress = (it.channel().remoteAddress() as InetSocketAddress)
            logger.info { "Connected as a server to $socketAddress" }
            prepareConnection(socketAddress, it)
        }
        .bindNow()

    @PreDestroy
    fun preDestroy() {
        server.disposeNow()
        outboundMap.values.forEach { it.tryEmitComplete() }
    }

    @EventListener
    @Async
    fun add(event: PeerAdded) {
        peers.add(event.peer.connectionSocketAddress)
    }

    @EventListener
    @Async
    fun remove(event: PeerRemoved) {
        peers.remove(event.peer.connectionSocketAddress)
    }

    @EventListener
    @Async
    fun ban(event: NodeBanned) {
        bannedNodes.add(event.address)
        disconnect(event.address)
    }

    @EventListener
    @Async
    fun outbound(message: OutboundNetworkMessage<*>) {
        val socketAddress = message.socketAddress

        if (disconnectionCache.getIfPresent(socketAddress.address) != null) {
            return
        }

        if (outboundMap.containsKey(message.socketAddress)) {
            tryEmit(message)
            return
        }

        TcpClient.create()
            .host(message.socketAddress.hostName)
            .port(message.socketAddress.port)
            .connect()
            .subscribe {
                logger.info { "Connected as a client to ${message.socketAddress}" }

                prepareConnection(message.socketAddress, it)

                tryEmit(message)
            }
    }

    fun tryEmit(message: OutboundNetworkMessage<*>) {
        val sink = outboundMap[message.socketAddress] ?: return

        sink.tryEmitNext(message)
    }

    private fun prepareConnection(socketAddress: InetSocketAddress, connection: Connection) {
        if (bannedNodes.contains(socketAddress.address)) {
            connection.dispose()
            return
        }

        val outbound = Sinks.many().unicast().onBackpressureBuffer<OutboundNetworkMessage<*>>()
        outboundMap[socketAddress] = outbound

        connection.inbound()
            .receive()
            .asByteArray()
            .doOnNext { logger.debug { "Received from $socketAddress ${it.toHex()}" } }
            .splitByMessage()
            .doOnSubscribe { logger.debug { "Subscribed to inbound messages from $socketAddress" } }
            .mapNotNull { deserializeOrDisconnect(socketAddress, it) }
            .map { InboundNetworkMessage(socketAddress, it!!) }
            .doOnTerminate { disconnect(socketAddress) }
            .subscribe { messagepublisher.publish(it!!) }

        val outboundMessages = outbound.asFlux()
            .map { serialize(it.payload) }
            .filter { isBelowMaxMessageSize(it) }
            .onBackpressureBuffer(maxOutboundBuffer)
            .doOnNext { logger.debug { "Sent to $socketAddress ${it.toHex()}" } }

        connection.outbound()
            .sendByteArray(outboundMessages)
            .then()
            .doOnSubscribe { logger.debug { "Subscribed to outbound messages from $socketAddress" } }
            .doOnError { t -> logger.info(t) { "Failed to send to $socketAddress" } }
            .doOnTerminate { disconnect(socketAddress) }
            .subscribe()
    }

    private fun serialize(message: AttoMessage): ByteArray {
        val byteBuffer = codecManager.toByteBuffer(message)
        logger.trace { "Serialized $message into ${byteBuffer.toHex()}" }
        return byteBuffer.toByteArray()
    }

    private fun deserializeOrDisconnect(
        socketAddress: InetSocketAddress,
        byteArray: ByteArray
    ): AttoMessage? {
        val message = codecManager.fromByteArray(AttoByteBuffer.from(byteArray))

        if (message == null) {
            logger.trace { "Received invalid message from $socketAddress ${byteArray.toHex()}. Node will be banned." }
            eventPublisher.publish(NodeBanned(socketAddress.address))
            return message
        } else if (message.messageType().private && !peers.contains(socketAddress)) {
            logger.trace { "Received private message from the unknown $socketAddress ${byteArray.toHex()}. Node will be banned." }
            eventPublisher.publish(NodeBanned(socketAddress.address))
            return message
        }

        logger.trace { "Deserialized $message from ${byteArray.toHex()}" }

        return message
    }

    /**
     * Just sanity test to avoid produce invalid data
     */
    private fun isBelowMaxMessageSize(byteArray: ByteArray): Boolean {
        if (byteArray.size - 8 > maxMessageSize) {
            logger.error { "Message longer than max size: ${byteArray.toHex()}" }
            return false
        }
        return true
    }

    private fun disconnect(socketAddress: InetSocketAddress) {
        val outbound = outboundMap.remove(socketAddress)
        outbound?.tryEmitComplete()
        logger.info { "Disconnected from $socketAddress" }
    }

    private fun disconnect(address: InetAddress) {
        outboundMap.keys().asSequence()
            .filter { it.address == address }
            .forEach { disconnect(it) }
    }

    override fun clear() {
        disconnectionCache.invalidateAll()
        outboundMap.values.forEach { it.tryEmitComplete() }
        bannedNodes.clear()
    }

    fun Flux<ByteArray>.splitByMessage(): Flux<ByteArray> {
        var previousByteArray: ByteArray? = null
        var readableBytesLeft = 0

        return flatMap { byteArray ->
            val messages = LinkedList<ByteArray>()

            if (readableBytesLeft > 0) {
                messages.add(previousByteArray!! + byteArray.sliceArray(0 until readableBytesLeft))
            }

            val byteArrayStartIndex = readableBytesLeft
            previousByteArray = null
            readableBytesLeft = 0

            if (byteArrayStartIndex < byteArray.size) {
                var currentByteArray: ByteArray? = byteArray.sliceArray(byteArrayStartIndex until byteArray.size)
                while (currentByteArray != null) {
                    val size = currentByteArray.sliceArray(6 until 8).toUShort().toInt() + headerSize
                    if (size > maxMessageSize) {
                        return@flatMap Flux.error(IllegalArgumentException("Message has $size bytes and it is longer than the $maxMessageSize limit"))
                    } else if (size > currentByteArray.size) {
                        previousByteArray = currentByteArray
                        readableBytesLeft = size - currentByteArray.size
                        currentByteArray = null
                    } else {
                        messages.add(currentByteArray.sliceArray(0 until size))
                        if (size < currentByteArray.size) {
                            currentByteArray = currentByteArray.sliceArray(size until currentByteArray.size)
                        } else {
                            currentByteArray = null
                        }
                    }
                }
            }
            return@flatMap Flux.fromIterable(messages)
        }
    }
}