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
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class ElectionProcessor(
    private val messagePublisher: NetworkMessagePublisher,
    private val accountService: AccountService,
    private val meterRegistry: MeterRegistry,
    transactionManager: ReactiveTransactionManager,
) {
    private val logger = KotlinLogging.logger {}

    private val buffer = ConcurrentLinkedDeque<ElectionConsensusReached>()
    private val bufferDepth = AtomicInteger()

    private val transactionalOperator = TransactionalOperator.create(transactionManager)
    private val flushMutex = Mutex()
    private lateinit var batchTimer: Timer
    private lateinit var batchSizeSummary: DistributionSummary

    @PostConstruct
    fun start() {
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
        buffer.addLast(event)
        bufferDepth.incrementAndGet()
    }

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun flush() {
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
        val events = drainBatch(size)

        try {
            if (events.isEmpty()) return 0

            val transactions = events.map { it.transaction }

            transactionalOperator.executeAndAwait {
                accountService.add(TransactionSource.ELECTION, transactions)
            }

            return events.size
        } catch (e: Exception) {
            requeue(events)
            throw RuntimeException("Error while processing ${events.map { it.transaction.hash }}", e)
        }
    }

    private fun drainBatch(size: Int): List<ElectionConsensusReached> {
        val events = mutableListOf<ElectionConsensusReached>()

        for (i in 1..size) {
            val event = buffer.pollFirst() ?: break
            events += event
        }

        if (events.isNotEmpty()) {
            bufferDepth.addAndGet(-events.size)
        }

        return events
    }

    private fun requeue(events: List<ElectionConsensusReached>) {
        events.asReversed().forEach { buffer.addFirst(it) }
        bufferDepth.addAndGet(events.size)
    }
}
