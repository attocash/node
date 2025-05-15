package cash.atto.node.network

import cash.atto.commons.toHex
import cash.atto.node.EventPublisher
import cash.atto.protocol.AttoKeepAlive
import cash.atto.protocol.AttoNode
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.InetSocketAddress
import java.net.URI
import java.time.Duration

@Component
class NodeConnectionManager(
    private val messagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val connectionMap =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofSeconds(60))
            .removalListener { _: URI?, connection: NodeConnection?, _ ->
                connection?.let {
                    runBlocking {
                        try {
                            connection.disconnected()
                        } finally {
                            eventPublisher.publish(NodeDisconnected(connection.connectionInetSocketAddress, connection.node))
                        }
                    }
                }
            }.build<URI, NodeConnection>()
            .asMap()

    @PreDestroy
    fun stop() {
        connectionMap.clear()
    }

    fun isConnected(publicUri: URI): Boolean = connectionMap.contains(publicUri)

    suspend fun manage(
        node: AttoNode,
        connectionSocketAddress: InetSocketAddress,
        session: WebSocketSession,
    ) {
        val publicUri = node.publicUri
        val connection = NodeConnection(node, connectionSocketAddress, session)

        val existingConnection = connectionMap.putIfAbsent(publicUri, connection)

        if (existingConnection != null) {
            logger.trace { "Connection to ${node.publicUri} already managed. New connection will be ignored" }
            connection.disconnected()
        }

        connection
            .incomingFlow()
            .onStart {
                eventPublisher.publish(NodeConnected(connectionSocketAddress, node))
            }.onCompletion {
                logger.trace(it) { "Inbound message stream from ${node.publicUri} completed" }
                connectionMap.remove(publicUri)
            }.collect {
                val message = NetworkSerializer.deserialize(it)

                if (message == null) {
                    logger.debug { "Received invalid message from $publicUri ${it.toHex()}" }
                    connectionMap.remove(publicUri)
                    return@collect
                }

                if (message is AttoKeepAlive) {
                    connectionMap[publicUri] = connection
                }

                logger.trace { "Received from $publicUri $message ${it.toHex()}" }

                val networkMessage =
                    InboundNetworkMessage(
                        MessageSource.WEBSOCKET,
                        publicUri,
                        connectionSocketAddress,
                        message,
                    )

                messagePublisher.publish(networkMessage)
            }
    }

    private suspend fun send(
        publicUri: URI,
        serialized: ByteArray,
    ) {
        connectionMap[publicUri]?.send(serialized)
    }

    @EventListener
    suspend fun send(networkMessage: DirectNetworkMessage<*>) {
        val publicUri = networkMessage.publicUri
        val message = networkMessage.payload
        val serialized = NetworkSerializer.serialize(message)

        logger.debug { "Sending to $publicUri $message ${serialized.toHex()}" }
        send(publicUri, serialized)
    }

    @EventListener
    suspend fun send(networkMessage: BroadcastNetworkMessage<*>) {
        val strategy = networkMessage.strategy
        val message = networkMessage.payload
        val serialized = NetworkSerializer.serialize(message)

        logger.trace { "Broadcasting peers $message ${serialized.toHex()}" }

        withContext(Dispatchers.Default) {
            connectionMap
                .values
                .shuffled()
                .asSequence()
                .filter { strategy.shouldBroadcast(it.node) }
                .forEach {
                    logger.trace { "Sending to ${it.node.publicUri} $message" }
                    send(it.node.publicUri, serialized)
                }
        }
    }

    @Scheduled(fixedRate = 10_000)
    suspend fun keepAlive() {
        val sample = connectionMap.values.randomOrNull()
        val message = AttoKeepAlive(sample?.node?.publicUri)
        send(BroadcastNetworkMessage(strategy = BroadcastStrategy.EVERYONE, payload = message))
    }

    private fun BroadcastStrategy.shouldBroadcast(node: AttoNode): Boolean =
        when (this) {
            BroadcastStrategy.EVERYONE -> true
            BroadcastStrategy.VOTERS -> node.isVoter()
        }

    private inner class NodeConnection(
        val node: AttoNode,
        val connectionInetSocketAddress: InetSocketAddress,
        val session: WebSocketSession,
    ) {
        fun incomingFlow(): Flow<ByteArray> =
            session
                .incoming
                .consumeAsFlow()
                .onStart { logger.info { "Connected to ${node.publicUri} ${node.publicKey}" } }
                .onCompletion { logger.info { "Disconnected from ${node.publicUri}" } }
                .map { it.readBytes() }

        suspend fun disconnected() {
            if (session.isActive) {
                session.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Disconnecting..."))
            }
        }

        suspend fun send(message: ByteArray) {
            session.outgoing.send(Frame.Binary(true, message))
        }
    }
}
