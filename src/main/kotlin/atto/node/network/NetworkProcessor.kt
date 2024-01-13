package atto.node.network


import atto.node.AsynchronousQueueProcessor
import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.codec.MessageCodecManager
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import atto.node.transaction.Transaction
import atto.protocol.network.AttoMessage
import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.toHex
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.netty.channel.AbortedException
import reactor.netty.http.client.HttpClient
import reactor.netty.http.client.WebsocketClientSpec
import reactor.netty.http.server.HttpServer
import reactor.netty.http.server.WebsocketServerSpec
import reactor.netty.http.websocket.WebsocketInbound
import reactor.netty.http.websocket.WebsocketOutbound
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds


@Service
class NetworkProcessor(
    val codecManager: MessageCodecManager,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
    val genesisTransaction: Transaction,
    environment: Environment,
) : AsynchronousQueueProcessor<OutboundNetworkMessage<*>>(1.milliseconds), CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val MAX_MESSAGE_SIZE = 1600
        const val GENESIS_HEADER = "Atto-Genesis"

        val serverSpec = WebsocketServerSpec.builder().maxFramePayloadLength(MAX_MESSAGE_SIZE).build()
        val clientSpec = WebsocketClientSpec.builder().maxFramePayloadLength(MAX_MESSAGE_SIZE).build()
    }

    private val peers = ConcurrentHashMap.newKeySet<InetSocketAddress>()
    private val bannedNodes = ConcurrentHashMap.newKeySet<InetAddress>()

    private val messageQueue = ConcurrentLinkedQueue<OutboundNetworkMessage<*>>()
    private val connections = ConcurrentHashMap<InetSocketAddress, InetSocketAddress>()
    private val outboundFlow = MutableSharedFlow<OutboundNetworkMessage<*>>()
    private val disconnectFlow = MutableSharedFlow<InetSocketAddress>()

    private val port = environment.getRequiredProperty("server.tcp.port", Int::class.java)

    private val eventLoopGroup: EventLoopGroup = NioEventLoopGroup(Thread.ofVirtual().factory())

    private val server = HttpServer.create()
        .port(port)
        .runOn(eventLoopGroup)
        .doOnBind {
            logger.info { "WebSocket started on port $port" }
        }
        .doOnConnection {
            val socketAddress = (it.channel().remoteAddress() as InetSocketAddress)
            logger.info { "Connected as a server to $socketAddress" }
        }
        .route { routes ->
            routes.ws(
                "/",
                { wsInbound, wsOutbound ->
                    if (wsInbound.headers().get(GENESIS_HEADER) == genesisTransaction.hash.toString()) {
                        prepareConnection(wsInbound, wsOutbound)
                    } else {
                        wsOutbound.sendClose()
                    }
                },
                serverSpec
            )
        }
        .bindNow()

    @PreDestroy
    fun stop() {
        clear()
        server.disposeNow()
        eventLoopGroup.shutdownGracefully().await().get()
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
    suspend fun ban(event: NodeBanned) {
        bannedNodes.add(event.address)
        connections.keys().asSequence()
            .filter { c -> c.address == event.address }
            .forEach { disconnectFlow.emit(it) }
    }

    @EventListener
    suspend fun outbound(message: OutboundNetworkMessage<*>) {
        val socketAddress = message.socketAddress

        connections.compute(socketAddress) { _, v ->
            if (v != null) {
                messageQueue.add(message)
            } else {
                HttpClient.create()
                    .runOn(eventLoopGroup)
                    .headers { it.add(GENESIS_HEADER, genesisTransaction.hash.toString()) }
                    .websocket(clientSpec)
                    .uri("ws://${message.socketAddress.hostName}:${message.socketAddress.port}")
                    .handle { inbound, outbound ->
                        prepareConnection(socketAddress, inbound, outbound, message)
                    }
                    .doOnSubscribe {
                        logger.info { "Connected as a client to ${message.socketAddress}" }
                    }
                    .subscribe()
            }
            socketAddress
        }


    }

    override suspend fun poll(): OutboundNetworkMessage<*>? {
        return messageQueue.poll()
    }

    override suspend fun process(value: OutboundNetworkMessage<*>) {
        outboundFlow.emit(value)
    }

    private fun getConnection(wsInbound: WebsocketInbound): InetSocketAddress? {
        var socketAddress: InetSocketAddress? = null

        wsInbound.withConnection {
            socketAddress = it.channel().remoteAddress() as InetSocketAddress
        }

        return socketAddress
    }

    private fun prepareConnection(
        wsInbound: WebsocketInbound,
        wsOutbound: WebsocketOutbound,
    ): Mono<Void> {
        val socketAddress = getConnection(wsInbound) ?: return wsOutbound.sendClose()
        return prepareConnection(socketAddress, wsInbound, wsOutbound)
            .doOnSubscribe { connections[socketAddress] = socketAddress }
    }

    private fun prepareConnection(
        socketAddress: InetSocketAddress,
        wsInbound: WebsocketInbound,
        wsOutbound: WebsocketOutbound,
        initialMessage: OutboundNetworkMessage<*>? = null,
    ): Mono<Void> {
        if (bannedNodes.contains(socketAddress.address)) {
            return wsOutbound.sendClose()
        }

        val inboundThen = wsInbound
            .receiveFrames()
            .flatMap {
                val content = it.content()
                if (!it.isFinalFragment) {
                    wsOutbound.sendClose().cast(AttoByteBuffer::class.java)
                } else if (content.hasArray()) {
                    Mono.just(AttoByteBuffer(content.array()))
                } else {
                    ByteArray(content.readableBytes()).also { byteArray ->
                        content.getBytes(content.readerIndex(), byteArray)
                    }.let { byteArray ->
                        Mono.just(AttoByteBuffer(byteArray))
                    }
                }
            }
            .doOnNext { logger.debug { "Received from $socketAddress ${it.toHex()}" } }
            .doOnSubscribe { logger.debug { "Subscribed to inbound messages from $socketAddress" } }
            .mapNotNull { deserializeOrDisconnect(socketAddress, it) { wsOutbound.sendClose().subscribe() } }
            .map { InboundNetworkMessage(socketAddress, it!!) }
            .doOnNext { messagePublisher.publish(it!!) }
            .onErrorResume(AbortedException::class.java) { Mono.empty() }
            .doOnError { t -> logger.info(t) { "Failed to process inbound message from $socketAddress" } }
            .retry(3)
            .then()

        val outboundMessages = outboundFlow
            .asFlux(Dispatchers.Default)
            .filter { it.socketAddress == socketAddress }
            .replay(1)
            .refCount()
            .let {
                val dummySubscription = it.subscribe()
                it.doOnTerminate { dummySubscription.dispose() }
            }
            .map { serialize(it.payload) }
            .doOnNext { checkBelowMaxMessageSize(it.serialized) }
            .doOnNext { logger.debug { "Sending to $socketAddress ${it.message} ${it.serialized.toHex()}" } }
            .map { it.serialized }
            .doOnSubscribe {
                if (initialMessage != null) {
                    messageQueue.add(initialMessage)
                }
            }

        val outboundThen = wsOutbound
            .sendByteArray(outboundMessages)
            .then()
            .doOnSubscribe { logger.debug { "Subscribed to outbound messages from $socketAddress" } }
            .doOnTerminate {
                connections.remove(socketAddress)
                eventPublisher.publish(NodeDisconnected(socketAddress))
            }
            .onErrorResume(AbortedException::class.java) { Mono.empty() }
            .doOnError { t -> logger.info(t) { "Failed to send to $socketAddress" } }
            .then()

        val disconnectionThen = disconnectFlow
            .filter { it == socketAddress }
            .asFlux(Dispatchers.Default)
            .next()
            .flatMap { wsOutbound.sendClose() }
            .onErrorComplete()

        return Flux.merge(inboundThen, outboundThen, disconnectionThen)
            .then()
    }

    private fun serialize(message: AttoMessage): SerializedAttoMessage {
        try {
            val byteBuffer = codecManager.toByteBuffer(message)
            logger.trace { "Serialized $message into ${byteBuffer.toHex()}" }
            return SerializedAttoMessage(message, byteBuffer.toByteArray())
        } catch (e: Exception) {
            logger.error(e) { "Message couldn't be serialized. $message" }
            exitProcess(-1)
        }
    }

    private fun deserializeOrDisconnect(
        socketAddress: InetSocketAddress,
        byteArray: AttoByteBuffer,
        disconnect: () -> Any
    ): AttoMessage? {
        val message = codecManager.fromByteArray(byteArray)

        if (message == null) {
            logger.trace { "Received invalid message from $socketAddress ${byteArray.toHex()}. Disconnecting..." }
            disconnect.invoke()
            return message
        } else if (message.messageType().private && !peers.contains(socketAddress)) {
            logger.trace { "Received private message from the unknown $socketAddress ${byteArray.toHex()}. Disconnecting..." }
            disconnect.invoke()
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

    override fun clear() {
        peers.clear()
        bannedNodes.clear()
        messageQueue.clear()

        connections.forEach {
            runBlocking { disconnectFlow.emit(it.key) }
        }
        connections.clear()
    }

    private data class SerializedAttoMessage(val message: AttoMessage, val serialized: ByteArray)

}