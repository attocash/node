package cash.atto.node.transaction.prioritization

import cash.atto.commons.AttoHash
import cash.atto.commons.PreviousSupport
import cash.atto.commons.ReceiveSupport
import cash.atto.node.CacheSupport
import cash.atto.node.DemandDrivenWorker
import cash.atto.node.DuplicateDetector
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountUpdated
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionDropped
import cash.atto.node.transaction.TransactionFailure
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.toTransaction
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@Service
class TransactionPrioritizer(
    properties: TransactionPrioritizationProperties,
    private val eventPublisher: EventPublisher,
    private val meterRegistry: MeterRegistry,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val queue = TransactionQueue(properties.groupMaxSize!!, 8)
    private val maxActiveElections = properties.maxActiveElections!!
    private val duplicateDetector = DuplicateDetector<AttoHash>(60.seconds)

    private val pendingTransactions = ConcurrentHashMap<AttoHash, ConcurrentHashMap<AttoHash, Transaction>>()
    private val worker = DemandDrivenWorker("transaction-prioritizer", drain = ::drain)

    @PostConstruct
    fun start() {
        Gauge
            .builder("transactions.prioritizer.queue.size", this) { it.getQueueSize().toDouble() }
            .description("Current transaction prioritizer queue size")
            .register(meterRegistry)
        Gauge
            .builder("transactions.prioritizer.pending.dependencies", this) { it.pendingTransactions.size.toDouble() }
            .description("Current transaction hashes awaiting validation, election, or persistence")
            .register(meterRegistry)
        Gauge
            .builder("transactions.prioritizer.buffer.size", this) { it.getBufferSize().toDouble() }
            .description("Current dependent transactions buffered by the prioritizer")
            .register(meterRegistry)
    }

    @PreDestroy
    fun stop() {
        worker.cancel()
    }

    private fun drain() {
        while (pendingTransactions.size < maxActiveElections) {
            val transaction =
                queue.poll { candidate ->
                    !pendingTransactions.containsKey(candidate.hash) &&
                        !bufferIfPending(candidate) &&
                        pendingTransactions.putIfAbsent(candidate.hash, ConcurrentHashMap()) == null
                } ?: return

            logger.debug { "Dequeued $transaction" }
            eventPublisher.publish(TransactionReceived(transaction))
        }
    }

    @EventListener
    fun add(message: InboundNetworkMessage<AttoTransactionPush>) {
        val transaction = message.payload.transaction

        if (pendingTransactions.containsKey(transaction.hash) || duplicateDetector.isDuplicate(transaction.hash)) {
            logger.trace { "Ignored duplicated $transaction" }
            return
        }

        add(transaction.toTransaction())
    }

    @EventListener
    fun process(event: AccountUpdated) {
        pendingTransactions.remove(event.transaction.hash)?.values?.forEach(::add)
        worker.request()
    }

    @EventListener
    fun process(event: TransactionFailure) {
        // A queue drop cannot complete a different processing attempt with the same hash.
        if (event is TransactionDropped) return

        val dependents = pendingTransactions.remove(event.transaction.hash)
        if (event.recoverable) {
            duplicateDetector.remove(event.transaction.hash)
        }
        dependents?.values?.forEach(::add)
        worker.request()
    }

    fun add(transaction: Transaction) {
        if (pendingTransactions.containsKey(transaction.hash) || bufferIfPending(transaction)) {
            return
        }

        val dropped = queue.add(transaction) { !pendingTransactions.containsKey(transaction.hash) }
        dropped?.let { eventPublisher.publish(TransactionDropped(it)) }
        worker.request()
    }

    private fun bufferIfPending(transaction: Transaction): Boolean {
        val block = transaction.block
        if (block is ReceiveSupport && bufferIfPending(block.sendHash, transaction)) {
            logger.debug { "Buffering ${transaction.hash} until send block ${block.sendHash} is confirmed" }
            return true
        }

        if (block is PreviousSupport && bufferIfPending(block.previous, transaction)) {
            logger.debug { "Buffering ${transaction.hash} until previous block ${block.previous} is confirmed" }
            return true
        }
        return false
    }

    private fun bufferIfPending(
        dependency: AttoHash,
        transaction: Transaction,
    ): Boolean =
        pendingTransactions.computeIfPresent(dependency) { _, dependents ->
            dependents.putIfAbsent(transaction.hash, transaction)
            dependents
        } != null

    fun getQueueSize(): Int = queue.size()

    fun getBufferSize(): Int = pendingTransactions.values.sumOf { it.size }

    override fun clear() {
        queue.clear()
        pendingTransactions.clear()
        duplicateDetector.clear()
    }
}
