package cash.atto.node.election

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.node.DemandDrivenWorker
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountService
import cash.atto.node.account.AccountUpdated
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
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.associate
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.concurrent.TimeUnit

@Service
class ElectionProcessor(
    private val messagePublisher: NetworkMessagePublisher,
    private val accountService: AccountService,
    private val accountRepository: AccountRepository,
    private val meterRegistry: MeterRegistry,
    transactionManager: ReactiveTransactionManager,
) {
    private val logger = KotlinLogging.logger {}

    // Owns transaction-hash deduplication, FIFO order, and in-flight membership.
    private val bufferLock = Any()
    private val buffer = LinkedHashMap<AttoHash, ElectionConsensusReached>()

    private val transactionalOperator = TransactionalOperator.create(transactionManager)
    private val persistenceMetrics = ElectionPersistenceMetrics(meterRegistry)
    private val worker = DemandDrivenWorker("election-processor", drain = ::drain)
    private lateinit var batchTimer: Timer
    private lateinit var batchSizeSummary: DistributionSummary

    @PostConstruct
    fun start() {
        Gauge
            .builder("elections.processor.buffer.size", this) { it.getBufferSize().toDouble() }
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
    fun process(event: AccountUpdated) {
        synchronized(bufferLock) {
            buffer.remove(event.transaction.hash)
        }
    }

    @EventListener
    suspend fun process(event: ElectionConsensusReached) {
        val shouldRequest =
            synchronized(bufferLock) { buffer.putIfAbsent(event.transaction.hash, event) == null }
        if (shouldRequest) worker.request()
    }

    @PreDestroy
    fun stop() {
        worker.cancel()
    }

    fun getBufferSize(): Int = synchronized(bufferLock) { buffer.size }

    private suspend fun drain() {
        while (getBufferSize() > 0) {
            flushBatchWithMetrics()
        }
    }

    private suspend fun flushBatchWithMetrics(): Int {
        val batchStarted = System.nanoTime()
        val processed = flushBatch(1_000)
        val batchFinished = System.nanoTime()
        if (processed > 0) {
            batchTimer.record(batchFinished - batchStarted, TimeUnit.NANOSECONDS)
            batchSizeSummary.record(processed.toDouble())
        }
        return processed
    }

    private suspend fun flushBatch(size: Int): Int {
        val batch = snapshotBatch(size)
        if (batch.isEmpty()) return 0

        val sample = persistenceMetrics.start()
        val transactions = batch.map { it.transaction }
        try {
            transactionalOperator.executeAndAwait { transaction ->
                sample.startBody(transaction)
                accountService.add(TransactionSource.ELECTION, transactions)
                sample.finishBody()
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (persistenceFailure: Exception) {
            val latestHeights =
                try {
                    accountRepository
                        .findAllById(transactions.map { it.publicKey })
                        .associate { it.publicKey to it.height }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (reconciliationFailure: Exception) {
                    if (reconciliationFailure !== persistenceFailure) {
                        persistenceFailure.addSuppressed(reconciliationFailure)
                    }
                    emptyMap()
                }

            val obsoleteHashes =
                transactions
                    .filter { transaction ->
                        val savedHeight = latestHeights[transaction.publicKey]?.toULong()
                        savedHeight != null && savedHeight >= transaction.height.value
                    }.mapTo(HashSet()) { it.hash }

            synchronized(bufferLock) {
                obsoleteHashes.forEach { buffer.remove(it) }
            }

            if (transactions.all { it.hash in obsoleteHashes }) return batch.size
            throw persistenceFailure
        }

        synchronized(bufferLock) {
            batch.forEach { buffer.remove(it.transaction.hash) }
        }
        sample.record()
        return batch.size
    }

    private fun snapshotBatch(size: Int): List<ElectionConsensusReached> =
        synchronized(bufferLock) {
            val publicKeys = HashSet<AttoPublicKey>()
            buffer.values.take(size).takeWhile { publicKeys.add(it.transaction.publicKey) }
        }
}
