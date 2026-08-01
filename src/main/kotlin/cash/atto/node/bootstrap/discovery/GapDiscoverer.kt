package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.PreviousSupport
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.UncheckedTransactionSaved
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
import java.time.Clock
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
    private val clock: Clock,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val peers = ConcurrentHashMap.newKeySet<URI>()

    private val mutex = Mutex()

    private val maxSize = 1_000L
    private val pointerMap =
        Caffeine
            .newBuilder()
            .maximumSize(maxSize)
            .build<AttoPublicKey, TransactionPointer>()
            .asMap()

    private val lastCompletedGaps =
        Caffeine
            .newBuilder()
            .scheduler(Scheduler.systemScheduler())
            .expireAfterWrite(Duration.ofMinutes(2))
            .build<AttoPublicKey, AttoHeight>()
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

    @EventListener
    fun process(event: UncheckedTransactionSaved) {
        if (lastCompletedGaps.remove(event.transaction.publicKey, event.transaction.block.height)) {
            logger.trace {
                "Removed last completed gap for account ${event.transaction.publicKey} with height ${event.transaction.block.height}"
            }
        }
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun resolve() {
        if (mutex.isLocked) {
            return
        }
        mutex.withLock {
            removeExpiredPointers()

            val peers = peers.toList()

            if (peers.isEmpty()) {
                return
            }

            val limit = maxSize - pointerMap.size

            if (limit <= 0L) {
                logger.debug { "Skipping gap discovery. Pointer map size is $maxSize" }
                return
            }

            if (pointerMap.isNotEmpty() || lastCompletedGaps.isNotEmpty()) {
                logger.trace {
                    "Pointer map size is ${pointerMap.size} and last completed gap is ${lastCompletedGaps.size}. Looking for $limit more gaps"
                }
            }

            val publicKeyToExclude =
                (pointerMap.keys + lastCompletedGaps.keys)
                    .ifEmpty { setOf(AttoPublicKey(ByteArray(32))) }

            val gaps = uncheckedTransactionRepository.findGaps(publicKeyToExclude, limit)

            gaps.collect { view ->
                val startHeight = view.startHeight()
                val endHeight = view.endHeight()
                val selectedPeer = peers[Random.nextInt(peers.size)]
                val pointer =
                    TransactionPointer(
                        publicKey = view.publicKey,
                        initialHeight = startHeight,
                        finalHeight = endHeight,
                        currentHeight = endHeight,
                        currentHash = view.expectedEndHash,
                        selectedPeer = selectedPeer,
                        expiresAt = clock.instant().plus(REQUEST_TIMEOUT),
                    )

                if (pointerMap.putIfAbsent(view.publicKey, pointer) == null) {
                    val request = AttoTransactionStreamRequest(view.publicKey, startHeight, endHeight)
                    val message =
                        DirectNetworkMessage(
                            selectedPeer,
                            request,
                            expectedResponseCount = endHeight.value - startHeight.value + 1UL,
                        )
                    networkMessagePublisher.publish(message)
                    logger.trace {
                        "Starting gap discovery for account ${view.publicKey}. Requesting transactions from $startHeight to $endHeight"
                    }
                }
            }
        }
    }

    @EventListener
    fun process(message: InboundNetworkMessage<AttoTransactionStreamResponse>) {
        val response = message.payload
        val transaction = response.transaction
        val block = transaction.block
        val discoveredTransactions = mutableListOf<AttoTransaction>()

        pointerMap.computeIfPresent(block.publicKey) { _, pointer ->
            // Recovery is retryable soft state, so stale or unrelated responses can be ignored without affecting ledger data.
            if (message.publicUri != pointer.selectedPeer || pointer.isExpired(clock.instant())) {
                return@computeIfPresent pointer
            }

            if (block.height !in pointer.initialHeight..pointer.finalHeight || block.height > pointer.currentHeight) {
                return@computeIfPresent pointer
            }

            if (block.height < pointer.currentHeight) {
                pointer.buffer(transaction)
                return@computeIfPresent pointer
            }

            if (transaction.hash != pointer.currentHash) {
                logger.debug { "Expecting transaction with hash ${pointer.currentHash} but received hash ${transaction.hash}" }
                return@computeIfPresent pointer
            }

            if (pointer.advance(transaction, discoveredTransactions)) {
                lastCompletedGaps[pointer.publicKey] = pointer.initialHeight
                return@computeIfPresent null
            }

            pointer
        }

        discoveredTransactions.forEach { discovered ->
            eventPublisher.publish(TransactionDiscovered(null, discovered.toTransaction(), listOf()))
        }
    }

    private fun TransactionPointer.advance(
        transaction: AttoTransaction,
        discoveredTransactions: MutableList<AttoTransaction>,
    ): Boolean {
        var currentTransaction = transaction

        while (true) {
            val block = currentTransaction.block
            discoveredTransactions.add(currentTransaction)

            if (initialHeight == block.height) {
                logger.debug { "End of the gap reached for account ${block.publicKey}" }
                return true
            }

            currentHeight = block.height - 1UL
            currentHash = (block as PreviousSupport).previous

            logger.debug {
                "Discovered gap transaction ${currentTransaction.hash} with height ${block.height}. " +
                    "Expecting hash $currentHash at height $currentHeight"
            }

            val bufferedTransaction = removeBuffered(currentHash)
            if (bufferedTransaction == null || bufferedTransaction.block.height != currentHeight) {
                return false
            }

            currentTransaction = bufferedTransaction
        }
    }

    private fun removeExpiredPointers() {
        val now = clock.instant()
        pointerMap.forEach { (publicKey, pointer) ->
            if (pointer.isExpired(now)) {
                pointerMap.remove(publicKey, pointer)
            }
        }
    }

    override fun clear() {
        pointerMap.clear()
        lastCompletedGaps.clear()
    }

    companion object {
        private val REQUEST_TIMEOUT = Duration.ofMinutes(1)
    }
}

private class TransactionPointer(
    val publicKey: AttoPublicKey,
    val initialHeight: AttoHeight,
    val finalHeight: AttoHeight,
    var currentHeight: AttoHeight,
    var currentHash: AttoHash,
    val selectedPeer: URI,
    val expiresAt: Instant,
) {
    private val requestedTransactionCount = (finalHeight - initialHeight + 1U).value.toInt()
    private val bufferedTransactions = HashMap<AttoHash, AttoTransaction>(requestedTransactionCount)

    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)

    fun buffer(transaction: AttoTransaction) {
        if (bufferedTransactions.size < requestedTransactionCount) {
            bufferedTransactions.putIfAbsent(transaction.hash, transaction)
        }
    }

    fun removeBuffered(expectedHash: AttoHash): AttoTransaction? = bufferedTransactions.remove(expectedHash)
}

private fun GapView.startHeight(): AttoHeight {
    val maxCount = AttoTransactionStreamRequest.MAX_TRANSACTIONS
    val count = this.endHeight - this.startHeight + 1U
    if (count.value > maxCount) {
        return this.endHeight - maxCount + 1U
    }
    return this.startHeight
}

private fun GapView.endHeight(): AttoHeight = this.endHeight
