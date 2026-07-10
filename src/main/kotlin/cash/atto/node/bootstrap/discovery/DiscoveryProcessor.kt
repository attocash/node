package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.node.CacheSupport
import cash.atto.node.DuplicateDetector
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.unchecked.UncheckedTransaction
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import cash.atto.node.bootstrap.unchecked.toUncheckedTransaction
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.measureTime

@Component
class DiscoveryProcessor(
    private val uncheckedTransactionService: UncheckedTransactionService,
    properties: DiscoveryProperties,
    meterRegistry: MeterRegistry,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val batchSize = properties.batchSize
    private val maxBatchesPerFlush = properties.maxBatchesPerFlush
    private val duplicateDetector = DuplicateDetector<AttoHash>(10.minutes, properties.queueMaxSize.toLong())
    private val buffer = ArrayBlockingQueue<PendingTransaction>(properties.queueMaxSize)
    private val flushInProgress = AtomicBoolean()
    private val admissionRejections =
        Counter
            .builder("bootstrap.discovery.admission.rejections")
            .description("Discovery transactions rejected because the queue is full")
            .register(meterRegistry)
    private val persistenceDrops =
        Counter
            .builder("bootstrap.discovery.persistence.drops")
            .description("Discovery transactions dropped after a persistence failure")
            .register(meterRegistry)

    init {
        Gauge
            .builder("bootstrap.discovery.queue.size", buffer) { it.size.toDouble() }
            .description("Current discovery transaction queue size")
            .register(meterRegistry)
    }

    override fun clear() {
        duplicateDetector.clear()
        buffer.clear()
    }

    @EventListener
    fun process(event: TransactionDiscovered) {
        val reservation = duplicateDetector.reserve(event.transaction.hash)
        if (reservation == null) {
            logger.trace { "Ignoring duplicate transaction ${event.transaction.hash}" }
            return
        }
        val pendingTransaction = PendingTransaction(event.transaction.toUncheckedTransaction(), reservation)
        if (!buffer.offer(pendingTransaction)) {
            duplicateDetector.remove(reservation)
            admissionRejections.increment()
        }
    }

    // Fixed rate avoids Spring's blocking suspend fixed-delay path; the CAS guard prevents overlapping flushes.
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun flush() {
        if (!flushInProgress.compareAndSet(false, true)) {
            return
        }

        try {
            repeat(maxBatchesPerFlush) {
                val batch = drainBatch()
                if (batch.isEmpty()) {
                    return
                }

                try {
                    val transactions = batch.map(PendingTransaction::transaction)
                    val elapsed =
                        measureTime {
                            uncheckedTransactionService.save(transactions)
                        }
                    logger.info { "Saved ${batch.size} unchecked transactions in $elapsed" }
                } catch (exception: Exception) {
                    releaseDroppedBatch(batch)
                    if (exception is CancellationException) {
                        throw exception
                    }
                    logger.warn(exception) {
                        "Dropped ${batch.size} retryable discovery transactions after persistence failed"
                    }
                    return
                }
            }
        } finally {
            flushInProgress.set(false)
        }
    }

    private fun drainBatch(): List<PendingTransaction> {
        val batch = ArrayList<PendingTransaction>(batchSize)
        while (batch.size < batchSize) {
            batch.add(buffer.poll() ?: break)
        }
        return batch
    }

    private fun releaseDroppedBatch(batch: Collection<PendingTransaction>) {
        // Discovery inputs are retryable soft state; dropping them does not mutate confirmed ledger state.
        batch.forEach { duplicateDetector.remove(it.reservation) }
        persistenceDrops.increment(batch.size.toDouble())
    }

    private data class PendingTransaction(
        val transaction: UncheckedTransaction,
        val reservation: DuplicateDetector.Reservation<AttoHash>,
    )
}
