package atto.node.network


import atto.node.AsynchronousQueueProcessor
import atto.node.CacheSupport
import atto.node.EventPublisher
import atto.node.network.peer.PeerAdded
import atto.node.network.peer.PeerRemoved
import atto.node.transaction.Transaction
import atto.protocol.AttoMessage
import atto.protocol.AttoNode
import cash.atto.commons.AttoByteBuffer
import cash.atto.commons.toHex
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.resolver.dns.DnsNameResolverBuilder
import io.netty.resolver.dns.DnsServerAddressStreamProviders
import io.netty.util.concurrent.Future
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
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds


@Service
class NetworkProcessor(
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher,
    private val genesisTransaction: Transaction,
    private val thisNode: AttoNode,
    environment: Environment,
) : AsynchronousQueueProcessor<OutboundNetworkMessage<*>>(1.milliseconds), CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val MAX_MESSAGE_SIZE = 255
        const val GENESIS_HEADER = "Atto-Genesis"
        const val PUBLIC_URI_HEADER = "Atto-Public-URI"
        const val PROTOCOL_VERSION_HEADER = "Atto-Protocol-Version"

        val serverSpec = WebsocketServerSpec.builder().maxFramePayloadLength(MAX_MESSAGE_SIZE).build()
        val clientSpec = WebsocketClientSpec.builder().maxFramePayloadLength(MAX_MESSAGE_SIZE).build()
    }

    private val peers = ConcurrentHashMap<URI, AttoNode>()
    private val bannedNodes = ConcurrentHashMap.newKeySet<InetAddress>()

    private val messageQueue = ConcurrentLinkedQueue<OutboundNetworkMessage<*>>()
    private val connections = ConcurrentHashMap<URI, URI>()
    private val outboundFlow = MutableSharedFlow<OutboundNetworkMessage<*>>()
    private val disconnectFlow = MutableSharedFlow<URI>()

    private val port = environment.getRequiredProperty("websocket.port", Int::class.java)

    private val eventLoopGroup: EventLoopGroup = NioEventLoopGroup(Thread.ofVirtual().factory())

    val dnsResolver = DnsNameResolverBuilder(eventLoopGroup.next())
        .channelType(NioDatagramChannel::class.java)
        .nameServerProvider(DnsServerAddressStreamProviders.platformDefault())
        .build()

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
                    try {
                        val connection = getConnection(wsInbound)

                        val headers = wsInbound.headers()

                        val genesis = headers.get(GENESIS_HEADER)
                        val publicUri = URI(headers.get(PUBLIC_URI_HEADER))
                        val protocolVersion = headers.get(PROTOCOL_VERSION_HEADER)?.toUShort() ?: 0U

                        dnsResolver.resolve(publicUri.host)
                            .toMono()
                            .flatMap {
                                val genesisMatches = genesis == genesisTransaction.hash.toString()
                                val connectionMatches = it == connection?.address
                                val protocolVersionMatches =
                                    thisNode.minProtocolVersion <= protocolVersion && protocolVersion <= thisNode.maxProtocolVersion

                                if (connection == null) {
                                    logger.debug {
                                        "Unable to resolve $publicUri"
                                    }
                                    wsOutbound.sendClose()
                                } else if (!genesisMatches) {
                                    logger.debug {
                                        "Received from $connection genesis $genesis. This is different from this node ${genesisTransaction.hash} genesis"
                                    }
                                    wsOutbound.sendClose()
                                } else if (!connectionMatches) {
                                    logger.debug {
                                        "Attempt connection from $connection which does not match the publicUri resolved ip"
                                    }
                                    wsOutbound.sendClose()
                                } else if (!protocolVersionMatches) {
                                    logger.debug {
                                        "Received from $connection $protocolVersion which is not supported"
                                    }
                                    wsOutbound.sendClose()
                                } else {
                                    connections[publicUri] = publicUri
                                    prepareConnection(publicUri, connection, wsInbound, wsOutbound)
                                }
                            }
                    } catch (e: Exception) {
                        wsOutbound.sendClose()
                    }
                },
                serverSpec
            )
        }
        .bindNow()

    @PreDestroy
    override fun stop() {
        clear()
        server.disposeNow()
        eventLoopGroup.shutdownGracefully().await().get()
        this.stop()
    }

    @EventListener
    fun add(event: PeerAdded) {
        peers[event.peer.node.publicUri] = event.peer.node
    }

    @EventListener
    fun remove(event: PeerRemoved) {
        peers.remove(event.peer.node.publicUri)
    }

    @EventListener
    suspend fun ban(event: NodeBanned) {
        bannedNodes.add(event.address)
        connections.keys().asSequence()
            .filter { InetAddress.getByName(it.host) == event.address }
            .forEach { disconnectFlow.emit(it) }
    }

    @EventListener
    suspend fun outbound(message: DirectNetworkMessage<*>) {
        val publicUri = message.publicUri

        connections.compute(publicUri) { _, v ->
            if (v != null) {
                messageQueue.add(message)
            } else {
                HttpClient.create()
                    .runOn(eventLoopGroup)
                    .headers {
                        it.add(GENESIS_HEADER, genesisTransaction.hash.toString())
                        it.add(PUBLIC_URI_HEADER, thisNode.publicUri)
                        it.add(PROTOCOL_VERSION_HEADER, thisNode.protocolVersion)
                    }
                    .websocket(clientSpec)
                    .uri(publicUri)
                    .handle { inbound, outbound ->
                        dnsResolver.resolve(publicUri.host)
                            .toMono()
                            .flatMap {
                                val port = if (publicUri.port != -1) publicUri.port else 80
                                prepareConnection(publicUri, InetSocketAddress(it, port), inbound, outbound, message)
                            }
                    }
                    .doOnSubscribe {
                        logger.info { "Connected as a client to $publicUri" }
                    }
                    .subscribe()
            }
            publicUri
        }
    }

    @EventListener
    suspend fun outbound(message: BroadcastNetworkMessage<*>) {
        messageQueue.add(message)
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
        publicUri: URI,
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
            .doOnNext { logger.debug { "Received from $publicUri ${it.toHex()}" } }
            .doOnSubscribe { logger.debug { "Subscribed to inbound messages from $publicUri" } }
            .mapNotNull { deserializeOrDisconnect(publicUri, it) { wsOutbound.sendClose().subscribe() } }
            .map { InboundNetworkMessage(publicUri, socketAddress, it!!) }
            .doOnNext { messagePublisher.publish(it!!) }
            .onErrorResume(AbortedException::class.java) { Mono.empty() }
            .doOnError { t -> logger.info(t) { "Failed to process inbound message from $publicUri" } }
            .then()

        val outboundMessages = outboundFlow
            .asFlux(Dispatchers.Default)
            .filter { it.accepts(publicUri, peers[publicUri]) }
            .replay(1)
            .refCount()
            .let {
                val dummySubscription = it.subscribe()
                it.doOnTerminate { dummySubscription.dispose() }
            }
            .map { serialize(it.payload) }
            .doOnNext { checkBelowMaxMessageSize(it.serialized) }
            .doOnNext { logger.debug { "Sending to $publicUri ${it.message} ${it.serialized.toHex()}" } }
            .map { it.serialized }

        val outboundThen = wsOutbound
            .sendByteArray(outboundMessages)
            .then()
            .doOnSubscribe { logger.debug { "Subscribed to outbound messages from $publicUri" } }
            .onErrorResume(AbortedException::class.java) { Mono.empty() }
            .doOnError { t -> logger.info(t) { "Failed to send to $publicUri. Retrying..." } }
            .retry(3)
            .doOnTerminate {
                connections.remove(publicUri)
                eventPublisher.publish(NodeDisconnected(publicUri))
            }
            .then()

        val disconnectionThen = disconnectFlow
            .filter { it == publicUri }
            .asFlux(Dispatchers.Default)
            .next()
            .flatMap { wsOutbound.sendClose() }
            .onErrorComplete()

        val isFirstRequest = AtomicBoolean(true)
        return Flux.merge(inboundThen, outboundThen, disconnectionThen)
            .doOnRequest {
                if (isFirstRequest.compareAndSet(true, false) && initialMessage != null) {
                    messageQueue.add(initialMessage)
                }
            }
            .then()
    }

    private fun serialize(message: AttoMessage): SerializedAttoMessage {
        try {
            val serialized = NetworkSerializer.serialize(message)

            val byteArray = serialized.toByteArray()

            logger.trace { "Serialized $message into ${serialized.toHex()}" }

            if (byteArray.size > MAX_MESSAGE_SIZE) {
                logger.error {
                    "Message ${message.messageType()} is ${byteArray.size - MAX_MESSAGE_SIZE} bytes longer " +
                            "than max size $MAX_MESSAGE_SIZE: ${byteArray.toHex()}"
                }
                exitProcess(-1)
            }

            return SerializedAttoMessage(message, byteArray)
        } catch (e: Exception) {
            logger.error(e) { "Message couldn't be serialized. $message" }
            exitProcess(-1)
        }
    }

    private fun deserializeOrDisconnect(
        publicUri: URI,
        byteBuffer: AttoByteBuffer,
        disconnect: () -> Any
    ): AttoMessage? {
        val message = NetworkSerializer.deserialize(byteBuffer)

        if (message == null) {
            logger.trace { "Received invalid message from $publicUri ${byteBuffer.toHex()}. Disconnecting..." }
            disconnect.invoke()
            return message
        } else if (message.messageType().private && !peers.containsKey(publicUri)) {
            logger.trace { "Received private message from the unknown $publicUri ${byteBuffer.toHex()}. Disconnecting..." }
            disconnect.invoke()
            return message
        }

        logger.trace { "Deserialized $message from ${byteBuffer.toHex()}" }

        return message
    }

    /**
     * Just sanity test to avoid produce invalid data
     */
    private fun checkBelowMaxMessageSize(byteArray: ByteArray) {
        if (byteArray.size > MAX_MESSAGE_SIZE) {
            logger.error { "Message ${byteArray.size - MAX_MESSAGE_SIZE} bytes longer than max size $MAX_MESSAGE_SIZE: ${byteArray.toHex()}" }
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

    @Suppress("UNCHECKED_CAST")
    private fun <T> Future<T>.toMono(): Mono<T> {
        val future = this;
        return Mono.create { sink ->
            future.addListener {
                if (it.isSuccess) {
                    sink.success(it.now as T)
                } else {
                    sink.error(it.cause())
                }
            }
        }
    }
}