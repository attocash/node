package cash.atto.node.network

import cash.atto.commons.toHex
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.attoCoroutineExceptionHandler
import cash.atto.node.network.peer.PeerAuthorized
import cash.atto.node.network.peer.PeerConnected
import cash.atto.node.network.peer.PeerRemoved
import cash.atto.node.transaction.Transaction
import cash.atto.protocol.AttoMessage
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoReady
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.origin
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.collections.set
import kotlin.system.exitProcess

@Service
class NetworkProcessor(
    private val eventPublisher: EventPublisher,
    private val messagePublisher: NetworkMessagePublisher,
    private val genesisTransaction: Transaction,
    private val thisNode: AttoNode,
    environment: Environment,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    init {
        logger.info { "NetworkProcessor initialized." }
    }

    companion object {
        const val MAX_MESSAGE_SIZE = 272
        const val GENESIS_HEADER = "Atto-Genesis"
        const val PUBLIC_URI_HEADER = "Atto-Public-Uri"
        const val PROTOCOL_VERSION_HEADER = "Atto-Protocol-Version"
        const val CHALLENGE_HEADER = "Atto-Http-Challenge"

        val random = SecureRandom.getInstanceStrong()!!
    }

    private val peers = ConcurrentHashMap<URI, PeerHolder>()

    private val bannedNodes = ConcurrentHashMap.newKeySet<InetAddress>()

    private val connections = ConcurrentHashMap<URI, URI>()
    private val disconnectFlow = MutableSharedFlow<URI>()
    private val outboundFlow = MutableSharedFlow<OutboundNetworkMessage<*>>(10_000)

    private val port = environment.getRequiredProperty("websocket.port", Int::class.java)

    val defaultScope = CoroutineScope(Dispatchers.Default + attoCoroutineExceptionHandler)

    private val challenges =
        Caffeine
            .newBuilder()
            .expireAfterWrite(5, TimeUnit.SECONDS)
            .maximumSize(100_000)
            .build<String, String>()
            .asMap()

    private val httpClient = HttpClient(CIO)

    private val websocketClient = HttpClient(CIO) {
        install(io.ktor.client.plugins.websocket.WebSockets) {
            maxFrameSize = MAX_MESSAGE_SIZE.toLong()
        }
    }

    private val server = embeddedServer(Netty, port = port) {
        install(io.ktor.server.websocket.WebSockets) {
            maxFrameSize = MAX_MESSAGE_SIZE.toLong()
        }
        routing {
            post("/challenges/{challenge}") {
                val challenge = call.parameters["challenge"] ?: ""
                if (challenges.remove(challenge) == null) {
                    logger.trace { "Received invalid challenge request: $challenge" }
                    call.respond(HttpStatusCode.NotFound)
                } else {
                    logger.trace { "Challenge $challenge validated successfully" }
                    call.respond(HttpStatusCode.OK)
                }
            }
            webSocket("/") {
                try {
                    logger.info { "New websocket connection attempt from ${call.request.origin.remoteHost}" }
                    val genesis = call.request.headers[GENESIS_HEADER]
                    val publicUri = URI(call.request.headers[PUBLIC_URI_HEADER])
                    val protocolVersion = call.request.headers[PROTOCOL_VERSION_HEADER]?.toUShort() ?: 0U
                    val challenge = call.request.headers[CHALLENGE_HEADER]

                    logger.trace { "Headers received: genesis=$genesis, publicUri=$publicUri, protocolVersion=$protocolVersion, challenge=$challenge" }

                    val httpUri = publicUri.toString()
                        .replaceFirst("wss://", "https://")
                        .replaceFirst("ws://", "http://")

                    val response = httpClient.post("$httpUri/challenges/$challenge")

                    logger.trace { "Challenge response status from $httpUri: ${response.status}" }

                    val genesisMatches = genesis == genesisTransaction.hash.toString()
                    val protocolVersionMatches =
                        thisNode.minProtocolVersion <= protocolVersion && protocolVersion <= thisNode.maxProtocolVersion

                    if (!genesisMatches) {
                        logger.debug {
                            "Received from $publicUri genesis $genesis. " +
                                "This is different from this node ${genesisTransaction.hash} genesis"
                        }
                        close(CloseReason(CloseReason.Codes.NORMAL, "Genesis mismatch"))
                    } else if (response.status != HttpStatusCode.OK) {
                        logger.debug {
                            "Attempt connection from $publicUri however client returned ${response.status}"
                        }
                        close(CloseReason(CloseReason.Codes.NORMAL, "Challenge validation failed"))
                    } else if (!protocolVersionMatches) {
                        logger.debug {
                            "Received from $publicUri $protocolVersion which is not supported"
                        }
                        close(CloseReason(CloseReason.Codes.NORMAL, "Protocol version not supported"))
                    } else {
                        logger.trace { "Connection from $publicUri accepted, preparing connection..." }
                        connections[publicUri] = publicUri
                        prepareConnection(publicUri, call.request.origin.remoteHost, this)
                    }
                } catch (e: Exception) {
                    logger.trace(e) { "Exception during handshake with ${call.request.origin.remoteHost}" }
                    close(CloseReason(CloseReason.Codes.NORMAL, "Exception during handshake"))
                }
            }
        }
    }.start(wait = false)

    @PreDestroy
    fun stop() {
        logger.trace { "Stopping NetworkProcessor, clearing resources and stopping server." }
        clear()
        server.stop(1000, 5000)
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
        logger.info { "Node banned: ${event.address}, disconnecting associated connections." }
        withContext(Dispatchers.IO) {
            connections
                .keys
                .asSequence()
                .filter { InetAddress.getByName(it.host) == event.address }
                .forEach { disconnectFlow.emit(it) }
        }
    }

    @EventListener
    suspend fun outbound(message: DirectNetworkMessage<*>) {
        val publicUri = message.publicUri
        logger.trace { "Processing outbound message to $publicUri: ${message.payload}" }

        connections.compute(publicUri) { _, v ->
            if (v != null) {
                defaultScope.launch {
                    logger.trace { "Emitting message to existing connection $publicUri" }
                    outboundFlow.emit(message)
                }
            } else {
                val challenge =
                    ByteArray(128).let {
                        random.nextBytes(it)
                        it.toHex()
                    }
                challenges[challenge] = challenge

                logger.debug { "No existing connection to $publicUri, initiating new connection with challenge $challenge" }

                defaultScope.launch {
                    try {
                        websocketClient.webSocket(
                            method = HttpMethod.Get,
                            host = publicUri.host,
                            port = if (publicUri.port == -1) 80 else publicUri.port,
                            path = publicUri.path,
                            request = {
                                headers.append(GENESIS_HEADER, genesisTransaction.hash.toString())
                                headers.append(PUBLIC_URI_HEADER, thisNode.publicUri.toString())
                                headers.append(PROTOCOL_VERSION_HEADER, thisNode.protocolVersion.toString())
                                headers.append(CHALLENGE_HEADER, challenge)
                            }
                        ) {
                            logger.info { "Connected as a client to $publicUri" }
                            val remoteHost = publicUri.host
                            prepareConnection(publicUri, remoteHost, this, message)
                        }
                    } catch (e: Exception) {
                        logger.error(e) { "Failed to connect to $publicUri" }
                    }
                }
            }
            publicUri
        }
    }

    @EventListener
    suspend fun outbound(message: BroadcastNetworkMessage<*>) {
        outboundFlow.emit(message)
    }

    private suspend fun prepareConnection(
        publicUri: URI,
        remoteHost: String,
        session: WebSocketSession,
        initialMessage: OutboundNetworkMessage<*>? = null,
    ) {
        logger.trace { "Preparing connection with $publicUri (remoteHost=$remoteHost)" }

        val remoteAddress = withContext(Dispatchers.IO) {
            InetAddress.getByName(remoteHost)
        }
        if (bannedNodes.contains(remoteAddress)) {
            logger.info { "Connection attempt from banned address $remoteAddress ($publicUri), closing session." }
            session.close(CloseReason(CloseReason.Codes.NORMAL, "Banned"))
            return
        }

        val inboundJob = defaultScope.launch {
            try {
                session.incoming.consumeAsFlow().collect { frame ->
                    if (frame is Frame.Binary) {
                        val data = frame.readBytes()
                        val buffer = Buffer()
                        buffer.write(data)
                        logger.debug { "Received from $publicUri ${buffer.toHex()}" }
                        val message = deserializeOrDisconnect(publicUri, buffer) {
                            defaultScope.launch {
                                logger.trace { "Invalid message from $publicUri, closing session." }
                                session.close(CloseReason(CloseReason.Codes.NORMAL, "Invalid message"))
                            }
                        }
                        if (message != null) {
                            logger.trace { "Received message $message from $publicUri" }
                            if (message == AttoReady && initialMessage != null) {
                                outboundFlow.emit(initialMessage)
                            } else {
                                val inboundMessage = InboundNetworkMessage(
                                    MessageSource.WEBSOCKET,
                                    publicUri,
                                    InetSocketAddress(remoteHost, 0),
                                    message
                                )
                                messagePublisher.publish(inboundMessage)
                            }
                        }
                    } else {
                        logger.trace { "Received non-binary frame from $publicUri, closing session." }
                        session.close(CloseReason(CloseReason.Codes.NORMAL, "Invalid frame type"))
                    }
                }
            } catch (e: Exception) {
                logger.info(e) { "Failed to process inbound message from $publicUri" }
            } finally {
                connections.remove(publicUri)
                eventPublisher.publish(NodeDisconnected(publicUri))
                logger.trace { "Connection with $publicUri closed." }
            }
        }

        val outboundJob = defaultScope.launch {
            try {
                outboundFlow
                    .onSubscription {
                        if (initialMessage == null) {
                            outboundFlow.emit(DirectNetworkMessage(publicUri, AttoReady))
                        }
                    }
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
                    }
                    .collect { outboundMessage ->
                        val serializedMessage = serialize(outboundMessage.payload)
                        checkBelowMaxMessageSize(serializedMessage)
                        logger.debug { "Sending to $publicUri ${serializedMessage.message} ${serializedMessage.serialized.toHex()}" }
                        session.outgoing.send(Frame.Binary(true, serializedMessage.serialized.readByteArray()))
                    }
            } catch (e: Exception) {
                logger.info(e) { "Failed to send to $publicUri. Retrying..." }
            } finally {
                connections.remove(publicUri)
                eventPublisher.publish(NodeDisconnected(publicUri))
                logger.trace { "Outbound job with $publicUri terminated." }
            }
        }

        val disconnectJob = defaultScope.launch {
            disconnectFlow
                .filter { it == publicUri }
                .collect {
                    logger.trace { "Disconnect signal received for $publicUri, closing session." }
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "Disconnected"))
                }
        }

        inboundJob.join()
        outboundJob.join()
        disconnectJob.cancel()
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
        try {
            val message = NetworkSerializer.deserialize(buffer)

            if (message == null) {
                logger.trace { "Received invalid message from $publicUri ${buffer.toHex()}. Disconnecting..." } // Changed log level
                disconnect.invoke()
                return null
            } else if (message.messageType().private && !peers.containsKey(publicUri)) {
                logger.trace { "Received private message from unknown $publicUri ${buffer.toHex()}. Disconnecting..." } // Changed log level
                disconnect.invoke()
                return null
            }

            logger.trace { "Deserialized $message from ${buffer.toHex()}" }

            return message
        } catch (e: Exception) {
            logger.trace(e) { "Error during deserialization from $publicUri" }
            disconnect.invoke()
            return null
        }
    }

    /**
     * Just sanity test to avoid producing invalid data
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
        logger.info { "Clearing NetworkProcessor caches and disconnecting peers." }
        peers.clear()
        bannedNodes.clear()

        connections.forEach {
            runBlocking { disconnectFlow.emit(it.key) }
        }
        connections.clear()
    }

    private data class SerializedAttoMessage(
        val message: AttoMessage,
        val serialized: Buffer,
    )

    private enum class PeerStatus {
        CONNECTED,
        AUTHORIZED,
    }

    private data class PeerHolder(
        val peerStatus: PeerStatus,
        val node: AttoNode,
    )
}
