package org.atto.node.network


import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import mu.KotlinLogging
import org.atto.commons.toHex
import org.atto.commons.toUShort
import org.atto.node.CacheSupport
import org.atto.node.network.codec.MessageCodecManager
import org.atto.protocol.Node
import org.atto.protocol.network.AttoMessage
import org.atto.protocol.network.ContextHolder
import org.springframework.context.event.EventListener
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
import javax.annotation.PreDestroy


@Service
class NetworkProcessor(
    val thisNode: Node,
    val codecManager: MessageCodecManager,
    val publisher: NetworkMessagePublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val maxMessageSize = 1600
        const val maxOutboundBuffer = 10_000
        const val headerSize = 8
    }

    private val outboundMap = ConcurrentHashMap<InetSocketAddress, Sinks.Many<OutboundNetworkMessage<*>>>()

    // Avoid event infinity loop when neighbour instantly disconnects
    var disconnectionCache: Cache<InetAddress, InetAddress> = Caffeine.newBuilder()
        .expireAfterWrite(60, TimeUnit.SECONDS)
        .build()

    private val server = TcpServer.create()
        .port(thisNode.socketAddress.port)
        .doOnBind {
            logger.info { "TCP started on ${thisNode.socketAddress.port} port" }
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
    }

    @EventListener
    fun startConnection(message: OutboundNetworkMessage<*>) {
        val socketAddress = message.socketAddress
        if (outboundMap.containsKey(message.socketAddress) || disconnectionCache.getIfPresent(socketAddress.address) != null) {
            return
        }

        TcpClient.create()
            .host(message.socketAddress.hostName)
            .port(message.socketAddress.port)
            .connect()
            .subscribe {
                logger.info { "Connected as a client to ${message.socketAddress}" }

                prepareConnection(message.socketAddress, it)

                // resend to outbound so outbound stream can see it
                outbound(message)
            }
    }

    @EventListener
    fun outbound(message: OutboundNetworkMessage<*>) {
        val sink = outboundMap[message.socketAddress] ?: return

        sink.tryEmitNext(message)
    }

    private fun prepareConnection(socketAddress: InetSocketAddress, connection: Connection) {
        val outbound = Sinks.many().unicast().onBackpressureBuffer<OutboundNetworkMessage<*>>()
        outboundMap[socketAddress] = outbound

        connection.inbound()
            .receive()
            .asByteArray()
            .doOnNext { logger.debug { "Received from $socketAddress ${it.toHex()}" } }
            .splitByMessage()
            .doOnSubscribe { logger.debug { "Subscribed to inbound messages from $socketAddress" } }
            .mapNotNull { deserializeOrDisconnect(socketAddress, it) }
            .map { InboundNetworkMessage(socketAddress, this, it!!) }
            .doOnTerminate { disconnect(socketAddress) }
            .subscribe { publisher.publish(it!!) }

        val outboundMessages = outbound.asFlux()
            .map { serialize(it.payload) }
            .filter { whenBelowMaxMessageSize(it) }
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
        val byteArray = codecManager.toByteArray(message)
        logger.trace { "Serialized $message into ${byteArray.toHex()}" }
        return byteArray
    }

    private fun deserializeOrDisconnect(
        socketAddress: InetSocketAddress,
        byteArray: ByteArray
    ): AttoMessage? {
        ContextHolder.put("socketAddress", socketAddress)

        try {
            val message = codecManager.fromByteArray(byteArray)

            if (message == null) {
                logger.trace { "Received invalid message from $socketAddress ${byteArray.toHex()}. Disconnecting..." }
                disconnect(socketAddress)
                return message
            }

            logger.trace { "Deserialized $message from ${byteArray.toHex()}" }

            return message
        } finally {
            ContextHolder.clear()
        }
    }

    /**
     * Just sanity test to avoid produce invalid data
     */
    private fun whenBelowMaxMessageSize(byteArray: ByteArray): Boolean {
        if (byteArray.size - 8 > maxMessageSize.toInt()) {
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

    override fun clear() {
        disconnectionCache.invalidateAll()
        outboundMap.values.forEach { it.tryEmitComplete() }
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