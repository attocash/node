package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.PreviousSupport
import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.unchecked.GapView
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.network.DirectNetworkMessage
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnected
import cash.atto.node.network.NodeDisconnected
import cash.atto.node.transaction.toTransaction
import cash.atto.protocol.AttoTransactionStreamRequest
import cash.atto.protocol.AttoTransactionStreamResponse
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.Scheduler
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.context.event.EventListener
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
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val networkMessagePublisher: NetworkMessagePublisher,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap.newKeySet<URI>()

    private val mutex = Mutex()

    private val maxSize = 1_000L
    private val pointerMap =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterAccess(Duration.ofMinutes(1))
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

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun resolve() {
        if (mutex.isLocked) {
            return
        }
        mutex.withLock {
            val peers = peers.toList()

            if (peers.isEmpty()) {
                return
            }

            val limit = maxSize - pointerMap.size
            val publicKeyToExclude =
                pointerMap.keys
                    .ifEmpty { setOf(AttoPublicKey(ByteArray(32))) }

            val gaps = uncheckedTransactionRepository.findGaps(publicKeyToExclude, limit)

            gaps.collect { view ->
                pointerMap.computeIfAbsent(view.publicKey) {
                    val startHeight = view.startHeight()
                    val endHeight = view.endHeight()
                    val request = AttoTransactionStreamRequest(view.publicKey, startHeight, endHeight)
                    val message =
                        DirectNetworkMessage(
                            peers[Random.nextInt(peers.size)],
                            request,
                            expectedResponseCount = endHeight.value - startHeight.value + 1UL,
                        )
                    networkMessagePublisher.publish(message)
                    TransactionPointer(startHeight, endHeight, view.expectedEndHash)
                }
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
            logger.debug { "Expecting transaction with hash ${pointer.currentHash} but received hash $transaction" }
            return pointer
        }

        val block = transaction.block

        val nextPointer =
            if (pointer.initialHeight == block.height) {
                logger.debug { "End of the gap reached for account ${transaction.block.publicKey}" }
                null
            } else {
                TransactionPointer(
                    pointer.initialHeight,
                    block.height - 1UL,
                    (block as PreviousSupport).previous,
                )
            }

        logger.debug { "Discovered gap transaction ${transaction.hash} with height ${block.height}. New pointer: $nextPointer" }

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
    val count = this.endHeight - this.startHeight + 1U
    if (count.value > maxCount) {
        return this.endHeight - maxCount + 1U
    }
    return this.startHeight
}

private fun GapView.endHeight(): AttoHeight = this.endHeight
