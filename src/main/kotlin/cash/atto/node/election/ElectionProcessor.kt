package cash.atto.node.election

import cash.atto.node.account.AccountService
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.BroadcastStrategy
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.vote.VoteService
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Service
class ElectionProcessor(
    private val thisNode: AttoNode,
    private val messagePublisher: NetworkMessagePublisher,
    private val accountService: AccountService,
    private val voteService: VoteService,
    private val meterRegistry: MeterRegistry,
    transactionManager: ReactiveTransactionManager,
) {
    private val logger = KotlinLogging.logger {}

    private val buffer = Channel<ElectionConsensusReached>(Channel.UNLIMITED)
    private val bufferDepth = AtomicInteger()

    private val transactionalOperator = TransactionalOperator.create(transactionManager)
    private val mutex = Mutex()
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
        bufferDepth.incrementAndGet()
        buffer.send(event)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.MILLISECONDS)
    suspend fun flush() {
        if (!mutex.tryLock()) {
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
            mutex.unlock()
        }
    }

    private suspend fun flushBatch(size: Int): Int {
        val events = mutableListOf<ElectionConsensusReached>()

        try {
            for (i in 1..size) {
                val event = buffer.tryReceive().getOrNull() ?: break
                bufferDepth.decrementAndGet()
                events += event
            }

            if (events.isEmpty()) return 0

            val transactions = events.map { it.transaction }

            transactionalOperator.executeAndAwait {
                accountService.add(TransactionSource.ELECTION, transactions)

                if (thisNode.isHistorical()) {
                    val finalVotes = events.flatMap { it.votes }.filter { it.isFinal() }
                    voteService.saveAll(finalVotes)
                }
            }

            return events.size
        } catch (e: Exception) {
            throw RuntimeException("Error while processing ${events.map { it.transaction.hash }}", e)
        }
    }
}
