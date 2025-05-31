package cash.atto.node.network

import cash.atto.commons.toHex
import cash.atto.node.EventPublisher
import cash.atto.protocol.AttoKeepAlive
import cash.atto.protocol.AttoNode
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readBytes
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
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

    private val ioScope =
        CoroutineScope(
            Dispatchers.IO + SupervisorJob(),
        )

    private val connectionMap =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(Duration.ofSeconds(60))
            .removalListener { _: URI?, connection: NodeConnection?, _ ->
                connection?.let {
                    ioScope.launch {
                        try {
                            connection.disconnect()
                        } finally {
                            eventPublisher.publish(NodeDisconnected(connection.connectionInetSocketAddress, connection.node))
                        }
                    }
                }
            }.build<URI, NodeConnection>()
            .asMap()

    val connectionCount: Int
        get() = connectionMap.size

    @PreDestroy
    fun stop() {
        connectionMap.clear()
    }

    fun isConnected(publicUri: URI): Boolean = connectionMap.containsKey(publicUri)

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
            connection.disconnect()
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

    @EventListener
    suspend fun send(networkMessage: DirectNetworkMessage<*>) {
        val publicUri = networkMessage.publicUri
        val message = networkMessage.payload
        val serialized = NetworkSerializer.serialize(message)

        logger.trace { "Sending to $publicUri $message ${serialized.toHex()}" }
        try {
            connectionMap[publicUri]?.send(serialized)
        } catch (e: Exception) {
            logger.debug(e) { "Exception during sending to $publicUri $message ${serialized.toHex()}" }
            connectionMap.remove(publicUri)
        }
    }

    @EventListener
    suspend fun send(networkMessage: BroadcastNetworkMessage<*>) {
        val strategy = networkMessage.strategy
        val message = networkMessage.payload
        val serialized = NetworkSerializer.serialize(message)

        connectionMap.values
            .asSequence()
            .filter { strategy.shouldBroadcast(it.node) }
            .forEach { connection ->
                ioScope.launch {
                    logger.trace { "Sending to ${connection.node.publicUri} $message" }
                    runCatching { connection.send(serialized) }
                        .onFailure { t ->
                            logger.debug(t) { "Exception during sending to ${connection.node.publicUri} $message" }
                            connectionMap.remove(connection.node.publicUri)
                        }
                }
            }
    }

    @Scheduled(fixedRate = 10_000)
    suspend fun keepAlive() {
        val sample = connectionMap.toMap().values.randomOrNull()
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
                .onCompletion { cause -> logger.info(cause) { "Disconnected from ${node.publicUri}" } }
                .map { it.readBytes() }

        fun disconnect() {
            session.cancel()
        }

        suspend fun send(message: ByteArray) {
            session.outgoing.send(Frame.Binary(true, message))
        }
    }
}
