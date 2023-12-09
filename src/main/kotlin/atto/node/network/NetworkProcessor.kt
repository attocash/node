package atto.node.network


import atto.node.AsynchronousQueueProcessor
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.netty.Connection
import reactor.netty.tcp.TcpClient
import reactor.netty.tcp.TcpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds


@Service
class NetworkProcessor(
    val codecManager: MessageCodecManager,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
    environment: Environment,
) : AsynchronousQueueProcessor<OutboundNetworkMessage<*>>(1.milliseconds), CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val MAX_MESSAGE_SIZE = 1600
        const val HEADER_SIZE = 8
    }

    private val peers = ConcurrentHashMap.newKeySet<InetSocketAddress>()
    private val bannedNodes = ConcurrentHashMap.newKeySet<InetAddress>()

    private val outboundMap = ConcurrentHashMap<InetSocketAddress, NodeConnection>()
    private val messageQueue = ConcurrentLinkedQueue<OutboundNetworkMessage<*>>()

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
    fun stop() {
        server.disposeNow()
        outboundMap.clear()
    }

    @EventListener
    fun add(event: PeerAdded) {
        peers.add(event.peer.connectionSocketAddress)
    }

    @EventListener
    fun remove(event: PeerRemoved) {
        peers.remove(event.peer.connectionSocketAddress)
    }

    @EventListener
    fun ban(event: NodeBanned) {
        bannedNodes.add(event.address)
        disconnect(event.address)
    }

    @EventListener
    suspend fun outbound(message: OutboundNetworkMessage<*>) {
        val socketAddress = message.socketAddress

        if (disconnectionCache.getIfPresent(socketAddress.address) != null) {
            return
        }

        if (outboundMap.containsKey(message.socketAddress)) {
            messageQueue.add(message)
            return
        }

        withContext(Dispatchers.IO) {
            TcpClient.create()
                .host(message.socketAddress.hostName)
                .port(message.socketAddress.port)
                .connect()
                .subscribe {
                    logger.info { "Connected as a client to ${message.socketAddress}" }
                    prepareConnection(message.socketAddress, it)
                    messageQueue.add(message)
                }
        }
    }

    override suspend fun poll(): OutboundNetworkMessage<*>? {
        return messageQueue.poll()
    }

    override suspend fun process(value: OutboundNetworkMessage<*>) {
        logger.trace { "Sending to ${value.socketAddress} ${value.payload}" }
        outboundMap[value.socketAddress]?.emit(value)
    }

    private fun prepareConnection(
        socketAddress: InetSocketAddress,
        connection: Connection,
    ) {
        if (bannedNodes.contains(socketAddress.address)) {
            connection.dispose()
            return
        }

        outboundMap.compute(socketAddress) { _, currentNodeConnection ->
            if (currentNodeConnection != null) {
                currentNodeConnection
            } else {
                val latch = CountDownLatch(2)

                val outbound = MutableSharedFlow<OutboundNetworkMessage<*>>(10)
                connection.inbound()
                    .receive()
                    .asByteArray()
                    .doOnNext { logger.debug { "Received from $socketAddress ${it.toHex()}" } }
                    .splitByMessage()
                    .doOnSubscribe { logger.debug { "Subscribed to inbound messages from $socketAddress" } }
                    .mapNotNull { deserializeOrDisconnect(socketAddress, it) }
                    .map { InboundNetworkMessage(socketAddress, it!!) }
                    .doOnError { t -> logger.info(t) { "Failed to process inbound message from $socketAddress" } }
                    .doOnSubscribe { latch.countDown() }
                    .doOnTerminate {
                        disconnect(socketAddress)
                    }
                    .subscribe { messagePublisher.publish(it!!) }

                val outboundMessages = outbound.asSharedFlow()
                    .map { serialize(it.payload) }
                    .onEach { checkBelowMaxMessageSize(it.serialized) }
                    .onEach { logger.debug { "Sending to $socketAddress ${it.message} ${it.serialized.toHex()}" } }
                    .map { it.serialized }

                val nodeConnection = NodeConnection(connection, outbound)

                connection.outbound()
                    .sendByteArray(outboundMessages.asFlux(Dispatchers.IO))
                    .then()
                    .doOnSubscribe { logger.debug { "Subscribed to outbound messages from $socketAddress" } }
                    .doOnError { t -> logger.info(t) { "Failed to send to $socketAddress" } }
                    .doOnSubscribe { latch.countDown() }
                    .doOnTerminate {
                        disconnect(socketAddress)
                    }
                    .subscribe()

                if (!latch.await(1, TimeUnit.SECONDS)) {
                    logger.warn { "Connection to $socketAddress took longer than 1 second. Disconnecting..." }
                    disconnect(socketAddress)
                    null
                } else {
                    nodeConnection
                }
            }
        }
    }

    private fun serialize(message: AttoMessage): SerializedAttoMessage {
        val byteBuffer = codecManager.toByteBuffer(message)
        logger.trace { "Serialized $message into ${byteBuffer.toHex()}" }
        return SerializedAttoMessage(message, byteBuffer.toByteArray())
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
    private fun checkBelowMaxMessageSize(byteArray: ByteArray) {
        if (byteArray.size - 8 > MAX_MESSAGE_SIZE) {
            logger.error { "Message longer than max size: ${byteArray.toHex()}" }
            exitProcess(-1)
        }
    }

    private fun disconnect(socketAddress: InetSocketAddress) {
        outboundMap.remove(socketAddress)?.disconnect()
        logger.info { "Disconnected from $socketAddress" }
    }

    private fun disconnect(address: InetAddress) {
        outboundMap.keys().asSequence()
            .filter { it.address == address }
            .forEach { disconnect(it) }
    }

    override fun clear() {
        disconnectionCache.invalidateAll()
        outboundMap.clear()
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
                    val size = currentByteArray.sliceArray(6 until 8).toUShort().toInt() + HEADER_SIZE
                    if (size > MAX_MESSAGE_SIZE) {
                        return@flatMap Flux.error(IllegalArgumentException("Message has $size bytes and it is longer than the $MAX_MESSAGE_SIZE limit"))
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

    private data class NodeConnection(
        val connection: Connection,
        val outbound: MutableSharedFlow<OutboundNetworkMessage<*>>
    ) {
        suspend fun emit(message: OutboundNetworkMessage<*>) {
            outbound.emit(message)
        }

        fun disconnect() {
            connection.dispose()
        }
    }

    private data class SerializedAttoMessage(val message: AttoMessage, val serialized: ByteArray)

}