package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.PreviousSupport
import cash.atto.commons.toAttoHeight
import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.unchecked.GapView
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.node.transaction.toTransaction
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.AttoTransactionStreamResponse
import com.github.benmanes.caffeine.cache.Caffeine
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactive.asFlow
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class GapDiscoverer(
    private val databaseClient: DatabaseClient,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap.newKeySet<URI>()

    private val maxSize = 100_000L
    private val pointerMap =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofMinutes(1))
            .maximumSize(maxSize)
            .build<AttoPublicKey, TransactionPointer>()
            .asMap()

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        val node = nodeEvent.node
        if (!node.isHistorical()) {
            return
        }
        peers.add(node.publicUri)
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        val node = nodeEvent.node
        peers.remove(node.publicUri)
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MINUTES)
    suspend fun resolve() {
        val peers = peers.toList()

        if (peers.isEmpty()) {
            return
        }

        val limit = maxSize - pointerMap.size

        val gaps =
            databaseClient
                .sql(
                    """
                        SELECT public_key, start_height, end_height, expected_end_hash
                        FROM (
                            SELECT ut.public_key AS public_key,
                                   COALESCE(LAG(ut.height) OVER (PARTITION BY ut.public_key ORDER BY ut.height ASC), 0) + 1 AS start_height,
                                   ut.height - 1 AS end_height,
                                   ut.previous AS expected_end_hash,
                                   ut.timestamp
                            FROM unchecked_transaction ut
                        ) ready
                        WHERE start_height <= end_height
                        ORDER BY timestamp
                        LIMIT ${limit};
                """,
                ).map { row, _ ->
                    GapView(
                        AttoPublicKey(row.get("public_key", ByteArray::class.java)!!),
                        row.get("start_height", Long::class.javaObjectType)!!.toULong().toAttoHeight(),
                        row.get("end_height", Long::class.javaObjectType)!!.toULong().toAttoHeight(),
                        AttoHash(row.get("expected_end_hash", ByteArray::class.java)!!),
                    )
                }.all()
                .asFlow() // https://github.com/spring-projects/spring-data-relational/issues/1394

        gaps.collect { view ->
            pointerMap.computeIfAbsent(view.publicKey) {
                val request = AttoTransactionStreamRequest(view.publicKey, view.startHeight(), view.endHeight())
                val message = DirectNetworkMessage(peers[Random.nextInt(peers.size)], request)
                networkMessagePublisher.publish(message)
                TransactionPointer(view.startHeight(), view.endHeight(), view.expectedEndHash)
            }
        }
    }

    @EventListener
    fun process(message: InboundNetworkMessage<AttoTransactionStreamResponse>) {
        val response = message.payload
        val transaction = response.transaction
        val block = transaction.block

        pointerMap.computeIfPresent(block.publicKey) { _, pointer ->
            process(pointer, transaction)
        }
    }

    private fun process(
        pointer: TransactionPointer,
        transaction: AttoTransaction,
    ): TransactionPointer? {
        if (transaction.hash != pointer.currentHash) {
            return pointer
        }

        val block = transaction.block

        val nextPointer =
            if (pointer.initialHeight == block.height) {
                null
            } else {
                TransactionPointer(
                    pointer.initialHeight,
                    pointer.currentHeight - 1UL,
                    (block as PreviousSupport).previous,
                )
            }

        logger.debug { "Discovered gap transaction ${transaction.hash}" }

        eventPublisher.publish(TransactionDiscovered(null, transaction.toTransaction(), listOf()))

        return nextPointer
    }
}

private data class TransactionPointer(
    val initialHeight: AttoHeight,
    val currentHeight: AttoHeight,
    val currentHash: AttoHash,
    val timestamp: Instant = Instant.now(),
)

private fun GapView.startHeight(): AttoHeight {
    val maxCount = AttoTransactionStreamRequest.MAX_TRANSACTIONS
    val count = this.endHeight - this.startHeight
    if (count.value > maxCount) {
        return this.endHeight - maxCount
    }
    return this.startHeight + 1U
}

private fun GapView.endHeight(): AttoHeight = this.endHeight
