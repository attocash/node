package cash.atto.node.election

import cash.atto.node.account.AccountService
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.TransactionSource
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.sync.Mutex
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@Service
class ElectionProcessor(
    private val messagePublisher: NetworkMessagePublisher,
    private val accountService: AccountService,
    private val properties: ElectionProperties,
    private val meterRegistry: MeterRegistry,
    transactionManager: ReactiveTransactionManager,
    private val clock: Clock,
) {
    private val logger = KotlinLogging.logger {}

    private val buffer = ConcurrentLinkedDeque<PendingElectionConsensus>()
    private val bufferDepth = AtomicInteger()

    private val transactionalOperator = TransactionalOperator.create(transactionManager)
    private val flushMutex = Mutex()
    private val nextFlushAt = AtomicReference(Instant.EPOCH)
    private lateinit var batchTimer: Timer
    private lateinit var batchSizeSummary: DistributionSummary

    @PostConstruct
    fun start() {
        require(properties.processingRetryMaxAttempts > 0) {
            "Election processor retry max attempts must be positive"
        }
        require(properties.processingRetryInitialBackoffInSeconds > 0) {
            "Election processor retry initial backoff must be positive"
        }
        require(properties.processingRetryMaxBackoffInSeconds >= properties.processingRetryInitialBackoffInSeconds) {
            "Election processor retry max backoff must be greater than or equal to initial backoff"
        }

        Gauge
            .builder("elections.processor.buffer.size", bufferDepth) { it.get().toDouble() }
            .description("Current election processor consensus buffer size")
            .register(meterRegistry)
        batchTimer =
            Timer
                .builder("elections.processor.batch")
                .description("Time processing an election consensus batch")
                .register(meterRegistry)
        batchSizeSummary =
            DistributionSummary
                .builder("elections.processor.batch.size")
                .description("Election consensus events processed per batch")
                .register(meterRegistry)
    }

    @EventListener
    fun process(event: ElectionExpiring) {
        val transaction = event.transaction
        val transactionPush = AttoTransactionPush(transaction.toAttoTransaction())

        logger.info { "Expiring transaction will be rebroadcasted $transaction" }

        messagePublisher.publish(
            BroadcastNetworkMessage(
                BroadcastStrategy.VOTERS,
                emptySet(),
                transactionPush,
            ),
        )
    }

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        buffer.addLast(PendingElectionConsensus(event, attempt = 1))
        bufferDepth.incrementAndGet()
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun flush() {
        val now = clock.instant()
        if (now < nextFlushAt.get()) {
            return
        }

        if (!flushMutex.tryLock()) {
            return
        }
        try {
            val batchStarted = System.nanoTime()
            val processed = flushBatch(1_000)
            val batchFinished = System.nanoTime()
            if (processed > 0) {
                batchTimer.record(batchFinished - batchStarted, TimeUnit.NANOSECONDS)
                batchSizeSummary.record(processed.toDouble())
            }
        } finally {
            flushMutex.unlock()
        }
    }

    fun getBufferSize(): Int = bufferDepth.get()

    private suspend fun flushBatch(size: Int): Int {
        val pendingEvents = drainBatch(size)

        try {
            if (pendingEvents.isEmpty()) return 0

            val transactions = pendingEvents.map { it.event.transaction }

            transactionalOperator.executeAndAwait {
                accountService.add(TransactionSource.ELECTION, transactions)
            }

            return pendingEvents.size
        } catch (e: Exception) {
            handleFailure(pendingEvents, e)
            return 0
        }
    }

    private fun drainBatch(size: Int): List<PendingElectionConsensus> {
        val events = mutableListOf<PendingElectionConsensus>()

        for (i in 1..size) {
            val event = buffer.pollFirst() ?: break
            events += event
        }

        if (events.isNotEmpty()) {
            bufferDepth.addAndGet(-events.size)
        }

        return events
    }

    private fun handleFailure(
        events: List<PendingElectionConsensus>,
        cause: Exception,
    ) {
        val (retryableEvents, droppedEvents) = events.partition { it.attempt < properties.processingRetryMaxAttempts }

        if (retryableEvents.isNotEmpty()) {
            requeue(retryableEvents)
            val backoff = backoffFor(retryableEvents.maxOf { it.attempt })
            nextFlushAt.set(clock.instant().plus(backoff))
            logger.warn(cause) {
                "Error while processing ${retryableEvents.map { it.event.transaction.hash }}. " +
                    "Retrying after election processor backoff of $backoff"
            }
        }

        if (droppedEvents.isNotEmpty()) {
            logger.error(cause) {
                "Dropping ${droppedEvents.map { it.event.transaction.hash }} after " +
                    "${properties.processingRetryMaxAttempts} election processor attempts"
            }
        }
    }

    private fun requeue(events: List<PendingElectionConsensus>) {
        events.asReversed().forEach {
            buffer.addFirst(it.copy(attempt = it.attempt + 1))
        }
        bufferDepth.addAndGet(events.size)
    }

    private fun backoffFor(attempt: Int): Duration {
        val multiplier = 1L shl (attempt - 1).coerceAtMost(30)
        val backoff = Duration.ofSeconds(properties.processingRetryInitialBackoffInSeconds).multipliedBy(multiplier)
        val maxBackoff = Duration.ofSeconds(properties.processingRetryMaxBackoffInSeconds)

        return if (backoff > maxBackoff) maxBackoff else backoff
    }

    private data class PendingElectionConsensus(
        val event: ElectionConsensusReached,
        val attempt: Int,
    )
}
