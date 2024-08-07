package cash.atto.node.network

import cash.atto.commons.toHex
import cash.atto.node.AsynchronousQueueProcessor
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.network.peer.PeerAuthorized
import cash.atto.node.network.peer.PeerConnected
import cash.atto.node.network.peer.PeerRemoved
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoMessage
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoReady
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.netty.buffer.Unpooled
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.util.concurrent.Future
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.runBlocking
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import kotlinx.io.write
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
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

@Service
class NetworkProcessor(
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher,
    private val genesisTransaction: Transaction,
    private val thisNode: AttoNode,
    environment: Environment,
) : AsynchronousQueueProcessor<OutboundNetworkMessage<*>>(1.milliseconds),
    CacheSupport {
    private val logger = KotlinLogging.logger {}

    companion object {
        const val MAX_MESSAGE_SIZE = 272
        const val GENESIS_HEADER = "Atto-Genesis"
        const val PUBLIC_URI_HEADER = "Atto-Public-Uri"
        const val PROTOCOL_VERSION_HEADER = "Atto-Protocol-Version"
        const val CHALLENGE_HEADER = "Atto-Http-Challenge"

        val random = SecureRandom.getInstanceStrong()!!
        val serverSpec = WebsocketServerSpec.builder().maxFramePayloadLength(MAX_MESSAGE_SIZE).build()
        val clientSpec = WebsocketClientSpec.builder().maxFramePayloadLength(MAX_MESSAGE_SIZE).build()
    }

    private val peers = ConcurrentHashMap<URI, PeerHolder>()

    private val bannedNodes = ConcurrentHashMap.newKeySet<InetAddress>()

    private val messageQueue = ConcurrentLinkedQueue<OutboundNetworkMessage<*>>()
    private val connections = ConcurrentHashMap<URI, URI>()
    private val outboundFlow = MutableSharedFlow<OutboundNetworkMessage<*>>()
    private val disconnectFlow = MutableSharedFlow<URI>()

    private val port = environment.getRequiredProperty("websocket.port", Int::class.java)

    private val eventLoopGroup: EventLoopGroup = NioEventLoopGroup(Thread.ofVirtual().factory())

    private val challenges =
        Caffeine
            .newBuilder()
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .maximumSize(100_000)
            .build<String, String>()
            .asMap()

    private val server =
        HttpServer
            .create()
            .port(port)
            .runOn(eventLoopGroup)
            .doOnBind {
                logger.info { "WebSocket started on port $port" }
            }.doOnConnection {
                val socketAddress = (it.channel().remoteAddress() as InetSocketAddress)
                logger.info { "Connected as a server to $socketAddress" }
            }.route { routes ->
                routes.post("/challenges/{challenge}") { request, response ->
                    val challenge = request.param("challenge") ?: ""

                    if (challenges.remove(challenge) == null) {
                        response.status(HttpResponseStatus.NOT_FOUND).send()
                    } else {
                        response.status(HttpResponseStatus.OK).send()
                    }
                }
                routes.ws(
                    "/",
                    { wsInbound, wsOutbound ->
                        try {
                            val connection = getConnection(wsInbound)

                            val headers = wsInbound.headers()

                            val genesis = headers.get(GENESIS_HEADER)
                            val publicUri = URI(headers.get(PUBLIC_URI_HEADER))
                            val protocolVersion = headers.get(PROTOCOL_VERSION_HEADER)?.toUShort() ?: 0U
                            val challenge = headers.get(CHALLENGE_HEADER)

                            val httpUri =
                                headers
                                    .get(PUBLIC_URI_HEADER)
                                    .replaceFirst("wss://", "https://")
                                    .replaceFirst("ws://", "http://")

                            HttpClient
                                .create()
                                .runOn(eventLoopGroup)
                                .post()
                                .uri("$httpUri/challenges/$challenge")
                                .send { _, outbound -> outbound }
                                .response { response, _ ->
                                    val genesisMatches = genesis == genesisTransaction.hash.toString()
                                    val protocolVersionMatches =
                                        thisNode.minProtocolVersion <= protocolVersion && protocolVersion <= thisNode.maxProtocolVersion

                                    if (connection == null) {
                                        logger.debug {
                                            "Unable to resolve $publicUri"
                                        }
                                        wsOutbound.sendClose()
                                    } else if (!genesisMatches) {
                                        logger.debug {
                                            "Received from $connection genesis $genesis. " +
                                                "This is different from this node ${genesisTransaction.hash} genesis"
                                        }
                                        wsOutbound.sendClose()
                                    } else if (response.status() != HttpResponseStatus.OK) {
                                        logger.debug {
                                            "Attempt connection from $connection however client returned ${response.status()}"
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
                                }.then()
                        } catch (e: Exception) {
                            wsOutbound.sendClose()
                        }
                    },
                    serverSpec,
                )
            }.bindNow()

    @PreDestroy
    override fun stop() {
        clear()
        server.disposeNow()
        eventLoopGroup.shutdownGracefully().await().get()
        this.stop()
    }

    @EventListener
    fun add(event: PeerAuthorized) {
        peers[event.peer.node.publicUri] = PeerHolder(PeerStatus.AUTHORIZED, event.peer.node)
    }

    @EventListener
    fun add(event: PeerConnected) {
        peers[event.peer.node.publicUri] = PeerHolder(PeerStatus.CONNECTED, event.peer.node)
    }

    @EventListener
    fun remove(event: PeerRemoved) {
        peers.remove(event.peer.node.publicUri)
    }

    @EventListener
    suspend fun ban(event: NodeBanned) {
        bannedNodes.add(event.address)
        connections
            .keys()
            .asSequence()
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
                val challenge =
                    ByteArray(128).let {
                        random.nextBytes(it)
                        it.toHex()
                    }
                challenges[challenge] = challenge

                HttpClient
                    .create()
                    .runOn(eventLoopGroup)
                    .headers {
                        it.add(GENESIS_HEADER, genesisTransaction.hash.toString())
                        it.add(PUBLIC_URI_HEADER, thisNode.publicUri)
                        it.add(PROTOCOL_VERSION_HEADER, thisNode.protocolVersion)
                        it.add(CHALLENGE_HEADER, challenge)
                    }.websocket(clientSpec)
                    .uri(publicUri)
                    .handle { inbound, outbound ->
                        var socketAddress: InetSocketAddress? = null

                        outbound.withConnection {
                            socketAddress = it.channel().remoteAddress() as InetSocketAddress
                        }

                        prepareConnection(publicUri, socketAddress!!, inbound, outbound, message)
                    }.doOnSubscribe {
                        logger.info { "Connected as a client to $publicUri" }
                    }.subscribe()
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

        val inboundThen =
            wsInbound
                .receiveFrames()
                .flatMap {
                    val content = it.content()
                    if (!it.isFinalFragment) {
                        wsOutbound.sendClose().cast(Buffer::class.java)
                    } else {
                        val buffer = Buffer()
                        buffer.write(content.nioBuffer())
                        Mono.just(buffer)
                    }
                }.doOnNext { logger.debug { "Received from $publicUri ${it.toHex()}" } }
                .doOnSubscribe { logger.debug { "Subscribed to inbound messages from $publicUri" } }
                .mapNotNull { deserializeOrDisconnect(publicUri, it) { wsOutbound.sendClose().subscribe() } }
                .filter {
                    if (it == AttoReady && initialMessage != null) {
                        messageQueue.add(initialMessage)
                        false
                    } else {
                        true
                    }
                }.map { InboundNetworkMessage(MessageSource.WEBSOCKET, publicUri, socketAddress, it!!) }
                .doOnNext { messagePublisher.publish(it!!) }
                .onErrorResume(AbortedException::class.java) { Mono.empty() }
                .doOnError { t -> logger.info(t) { "Failed to process inbound message from $publicUri" } }
                .doOnTerminate {
                    connections.remove(publicUri)
                    eventPublisher.publish(NodeDisconnected(publicUri))
                }.then()

        val outboundMessages =
            outboundFlow
                .asFlux(Dispatchers.Default)
                .filter {
                    val connectedNode =
                        peers[publicUri]?.let {
                            if (it.peerStatus == PeerStatus.CONNECTED) {
                                it.node
                            } else {
                                null
                            }
                        }
                    it.accepts(publicUri, connectedNode)
                }.replay(1)
                .refCount()
                .let {
                    val dummySubscription = it.subscribe()
                    it.doOnTerminate { dummySubscription.dispose() }
                }.map { serialize(it.payload) }
                .doOnNext { checkBelowMaxMessageSize(it) }
                .doOnNext { logger.debug { "Sending to $publicUri ${it.message} ${it.serialized.toHex()}" } }
                .map {
                    Unpooled.wrappedBuffer(it.serialized.readByteArray())
                }.doOnSubscribe {
                    if (initialMessage == null) {
                        messageQueue.add(DirectNetworkMessage(publicUri, AttoReady))
                    }
                }

        val outboundThen =
            wsOutbound
                .send(outboundMessages)
                .then()
                .doOnSubscribe { logger.debug { "Subscribed to outbound messages from $publicUri" } }
                .onErrorResume(AbortedException::class.java) { Mono.empty() }
                .doOnError { t -> logger.info(t) { "Failed to send to $publicUri. Retrying..." } }
                .retry(3)
                .doOnTerminate {
                    connections.remove(publicUri)
                    eventPublisher.publish(NodeDisconnected(publicUri))
                }.then()

        val disconnectionThen =
            disconnectFlow
                .filter { it == publicUri }
                .asFlux(Dispatchers.Default)
                .next()
                .flatMap { wsOutbound.sendClose() }
                .onErrorComplete()

        return Flux
            .merge(inboundThen, outboundThen, disconnectionThen)
            .then()
    }

    private fun serialize(message: AttoMessage): SerializedAttoMessage {
        try {
            val serialized = NetworkSerializer.serialize(message)

            logger.trace { "Serialized $message into ${serialized.toHex()}" }

            if (serialized.size > MAX_MESSAGE_SIZE) {
                logger.error {
                    "Message ${message.messageType()} has ${serialized.size} which is ${serialized.size - MAX_MESSAGE_SIZE} bytes longer " +
                        "than max size $MAX_MESSAGE_SIZE: ${serialized.toHex()}"
                }
                exitProcess(-1)
            }

            return SerializedAttoMessage(message, serialized)
        } catch (e: Exception) {
            logger.error(e) { "Message couldn't be serialized. $message" }
            exitProcess(-1)
        }
    }

    private fun deserializeOrDisconnect(
        publicUri: URI,
        buffer: Buffer,
        disconnect: () -> Any,
    ): AttoMessage? {
        val message = NetworkSerializer.deserialize(buffer)

        if (message == null) {
            logger.trace { "Received invalid message from $publicUri ${buffer.toHex()}. Disconnecting..." }
            disconnect.invoke()
            return message
        } else if (message.messageType().private && !peers.containsKey(publicUri)) {
            logger.trace { "Received private message from the unknown $publicUri ${buffer.toHex()}. Disconnecting..." }
            disconnect.invoke()
            return message
        }

        logger.trace { "Deserialized $message from ${buffer.toHex()}" }

        return message
    }

    /**
     * Just sanity test to avoid produce invalid data
     */
    private fun checkBelowMaxMessageSize(serializeMessage: SerializedAttoMessage) {
        val byteArray = serializeMessage.serialized
        val message = serializeMessage.message
        if (byteArray.size > MAX_MESSAGE_SIZE) {
            logger.error {
                "Message has ${byteArray.size} which is ${byteArray.size - MAX_MESSAGE_SIZE} bytes longer " +
                    "than max size $MAX_MESSAGE_SIZE: ${byteArray.toHex()}"
            }
            logger.error {
                "Message ${message.messageType()} has ${byteArray.size} which is ${byteArray.size - MAX_MESSAGE_SIZE} bytes longer " +
                    "than max size $MAX_MESSAGE_SIZE: ${byteArray.toHex()}"
            }
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

    private data class SerializedAttoMessage(
        val message: AttoMessage,
        val serialized: Buffer,
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> Future<T>.toMono(): Mono<T> {
        val future = this
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

    private enum class PeerStatus {
        CONNECTED,
        AUTHORIZED,
    }

    private data class PeerHolder(
        val peerStatus: PeerStatus,
        val node: AttoNode,
    )
}
