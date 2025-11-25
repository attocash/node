package cash.atto.node.transaction.priotization

import cash.atto.commons.AttoHash
import cash.atto.commons.PreviousSupport
import cash.atto.commons.ReceiveSupport
import cash.atto.node.CacheSupport
import cash.atto.node.DuplicateDetector
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.ElectionExpired
import cash.atto.node.election.ElectionStarted
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionDropped
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.toTransaction
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@Service
class TransactionPrioritizer(
    properties: TransactionPrioritizationProperties,
    private val eventPublisher: EventPublisher,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val queue = TransactionQueue(properties.groupMaxSize!!, 8)
    private val duplicateDetector = DuplicateDetector<AttoHash>(60.seconds)
    private val electionDependencies = ConcurrentHashMap<AttoHash, MutableSet<Transaction>>()

    @Scheduled(fixedRateString = "\${atto.transaction.prioritization.frequency}")
    fun process() {
        do {
            val electionsSize = electionDependencies.size
            if (electionsSize >= 1000) {
                logger.debug { "There are $electionsSize active elections. Skipping prioritization for now." }
                return
            }

            val transaction = queue.poll()

            transaction?.let {
                logger.debug { "Dequeued $transaction" }
                eventPublisher.publish(TransactionReceived(it))
            }
        } while (transaction != null)
    }

    @EventListener
    fun add(message: InboundNetworkMessage<AttoTransactionPush>) {
        val transaction = message.payload.transaction

        if (duplicateDetector.isDuplicate(transaction.hash)) {
            logger.trace { "Ignored duplicated $transaction" }
            return
        }

        add(transaction.toTransaction())
    }

    @EventListener
    fun process(event: AccountUpdated) {
        val hash = event.transaction.hash

        val bufferedTransactions = electionDependencies.remove(hash) ?: emptySet()

        if (bufferedTransactions.isNotEmpty()) {
            logger.debug { "Dependency $hash resolved. Re-processing ${bufferedTransactions.size} transactions." }
            bufferedTransactions.forEach {
                add(it)
            }
        }
    }

    @EventListener
    fun process(event: ElectionStarted) {
        electionDependencies.putIfAbsent(event.transaction.hash, ConcurrentHashMap.newKeySet())
    }

    @EventListener
    fun process(event: ElectionExpired) {
        electionDependencies.remove(event.transaction.hash)
    }

    fun add(transaction: Transaction) {
        val block = transaction.block

        if (block is ReceiveSupport && bufferIfElectionActive(block.sendHash, transaction)) {
            logger.debug { "Buffering ${transaction.hash} until send block ${block.sendHash} is confirmed" }
            return
        }

        if (block is PreviousSupport && bufferIfElectionActive(block.previous, transaction)) {
            logger.debug { "Buffering ${transaction.hash} until previous block ${block.previous} is confirmed" }
            return
        }

        val droppedTransaction = queue.add(transaction)

        if (droppedTransaction != null) {
            logger.debug { "Dropped $droppedTransaction" }
            eventPublisher.publish(TransactionDropped(droppedTransaction))
        } else {
            logger.debug { "Queued $transaction" }
        }
    }

    private fun bufferIfElectionActive(
        dependency: AttoHash,
        transaction: Transaction,
    ): Boolean {
        val dependencies =
            electionDependencies.computeIfPresent(dependency) { _, set ->
                set.add(transaction)
                set
            }
        return dependencies != null
    }

    fun getQueueSize(): Int = queue.size()

    fun getBufferSize(): Int = electionDependencies.values.sumOf { it.size }

    override fun clear() {
        queue.clear()
        electionDependencies.clear()
        duplicateDetector.clear()
    }
}
