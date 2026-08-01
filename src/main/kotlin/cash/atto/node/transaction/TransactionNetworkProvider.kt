package cash.atto.node.transaction

import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionRequest
import cash.atto.protocol.AttoTransactionResponse
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.AttoTransactionStreamResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@Component
class TransactionNetworkProvider(
    private val thisNode: AttoNode,
    private val transactionRepository: TransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
) {
    private val logger = KotlinLogging.logger {}

    // The peers ConcurrentHashMap owns membership only; the mutex deliberately limits active database work to one.
    // The semaphore admits 1,001 active or waiting requests (1 active + 1,000 pending); fairness remains deferred.
    private val peers = ConcurrentHashMap.newKeySet<URI>()
    private val mutex = Mutex()
    private val requestPermits = Semaphore(MAX_ADMITTED_REQUESTS)

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        peers.add(nodeEvent.node.publicUri)
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        peers.remove(nodeEvent.node.publicUri)
    }

    @EventListener
    suspend fun find(message: InboundNetworkMessage<AttoTransactionRequest>) {
        if (thisNode.isNotHistorical()) {
            return
        }

        serve(message.publicUri) {
            val transaction = transactionRepository.findById(message.payload.hash)
            if (transaction != null) {
                val response = AttoTransactionResponse(transaction.toAttoTransaction())
                networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
            }
        }
    }

    @EventListener
    suspend fun stream(message: InboundNetworkMessage<AttoTransactionStreamRequest>) {
        if (thisNode.isNotHistorical()) {
            return
        }

        serve(message.publicUri) {
            val request = message.payload
            transactionRepository
                .findDesc(
                    request.publicKey,
                    request.startHeight,
                    request.endHeight,
                ).takeWhile { peers.contains(message.publicUri) }
                .collect {
                    val response = AttoTransactionStreamResponse(it.toAttoTransaction())
                    networkMessagePublisher.publish(DirectNetworkMessage(message.publicUri, response))
                    delay(STREAM_PACING_MILLIS.milliseconds)
                }
        }
    }

    private suspend fun serve(
        publicUri: URI,
        operation: suspend () -> Unit,
    ) {
        if (!requestPermits.tryAcquire()) {
            logger.debug { "Dropping historical transaction request from $publicUri because provider capacity is full" }
            return
        }

        try {
            withTimeoutOrNull(REQUEST_TIMEOUT) {
                mutex.withLock {
                    if (!peers.contains(publicUri)) {
                        return@withLock
                    }
                    operation()
                }
            }
        } finally {
            requestPermits.release()
        }
    }

    companion object {
        internal const val MAX_PENDING_REQUESTS = 1_000
        internal const val ACTIVE_REQUEST_PERMITS = 1
        internal const val MAX_ADMITTED_REQUESTS = MAX_PENDING_REQUESTS + ACTIVE_REQUEST_PERMITS
        internal const val STREAM_PACING_MILLIS = 10L
        internal val REQUEST_TIMEOUT = 60.seconds
    }
}
