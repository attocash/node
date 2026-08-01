package cash.atto.node.transaction.prioritization

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
import cash.atto.node.transaction.PublicKeyHeight
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionDropped
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.toTransaction
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
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
    private val dependencyMaxSize = properties.dependencyMaxSize
    private val bufferMaxSize = properties.bufferMaxSize
    private val duplicateDetector = DuplicateDetector<AttoHash>(60.seconds)

    // Guards electionDependencies, candidateHashes, their nested sets, and bufferedTransactionCount
    // as one registration/removal/limit invariant.
    private val dependencyStateLock = ReentrantLock()
    private val electionDependencies = mutableMapOf<AttoHash, MutableSet<Transaction>>()
    private val candidateHashes = mutableMapOf<PublicKeyHeight, MutableSet<AttoHash>>()
    private var bufferedTransactionCount = 0

    @PostConstruct
    fun start() {
        Gauge
            .builder("transactions.prioritizer.queue.size", this) { it.getQueueSize().toDouble() }
            .description("Current transaction prioritizer queue size")
            .register(meterRegistry)
        Gauge
            .builder("transactions.prioritizer.pending.dependencies", this) {
                it.getPendingDependencyCount().toDouble()
            }.description("Current transaction dependency hashes waiting for account update")
            .register(meterRegistry)
        Gauge
            .builder("transactions.prioritizer.buffer.size", this) { it.getBufferSize().toDouble() }
            .description("Current dependent transactions buffered by the prioritizer")
            .register(meterRegistry)
    }

    @Scheduled(fixedRateString = "\${atto.transaction.prioritization.frequency}")
    fun process() {
        do {
            val pendingDependencyCount = getPendingDependencyCount()
            if (pendingDependencyCount >= maxActiveElections) {
                logger.debug {
                    "There are $pendingDependencyCount transaction dependencies pending account update. " +
                        "Skipping prioritization for now."
                }
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
        val bufferedTransactions =
            removeCandidates(event.transaction.toPublicKeyHeight(), hash, hash).bufferedTransactions

        if (bufferedTransactions.isNotEmpty()) {
            logger.debug { "Dependency $hash resolved. Re-processing ${bufferedTransactions.size} transactions." }
            bufferedTransactions.forEach {
                add(it)
            }
        }
    }

    @EventListener
    fun process(event: ElectionStarted) {
        val transaction = event.transaction
        val registered =
            dependencyStateLock.withLock {
                if (electionDependencies.containsKey(transaction.hash)) {
                    candidateHashes
                        .getOrPut(transaction.toPublicKeyHeight()) { mutableSetOf() }
                        .add(transaction.hash)
                    true
                } else if (electionDependencies.size >= maxActiveElections) {
                    false
                } else {
                    electionDependencies[transaction.hash] = mutableSetOf()
                    candidateHashes
                        .getOrPut(transaction.toPublicKeyHeight()) { mutableSetOf() }
                        .add(transaction.hash)
                    true
                }
            }

        if (!registered) {
            logger.warn { "Ignored dependency registration for ${transaction.hash}: active election limit reached" }
        }
    }

    @EventListener
    fun process(event: ElectionExpired) {
        val removed = removeCandidates(event.transaction.toPublicKeyHeight(), event.transaction.hash)
        removed.candidateHashes.forEach(duplicateDetector::remove)
    }

    @EventListener
    fun process(event: TransactionRejected) {
        if (event.reason.recoverable) {
            duplicateDetector.remove(event.transaction.hash)
        }
    }

    fun add(transaction: Transaction) {
        val block = transaction.block

        if (block is ReceiveSupport && handleActiveDependency(block.sendHash, transaction)) {
            return
        }

        if (block is PreviousSupport && handleActiveDependency(block.previous, transaction)) {
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

    private fun handleActiveDependency(
        dependency: AttoHash,
        transaction: Transaction,
    ): Boolean {
        val postLockAction: () -> Unit =
            dependencyStateLock.withLock {
                val dependencies = electionDependencies[dependency] ?: return false
                when {
                    transaction in dependencies -> {
                        {
                            logger.trace {
                                "Ignored duplicate dependent ${transaction.hash} for active dependency $dependency"
                            }
                        }
                    }

                    dependencies.size >= dependencyMaxSize || bufferedTransactionCount >= bufferMaxSize -> {
                        {
                            // This is retryable pre-consensus state. Keep duplicate suppression to prevent a hot retry loop.
                            logger.debug { "Dropped dependent $transaction" }
                            eventPublisher.publish(TransactionDropped(transaction))
                        }
                    }

                    else -> {
                        dependencies.add(transaction)
                        bufferedTransactionCount++
                        {
                            logger.debug { "Buffering ${transaction.hash} until dependency $dependency is confirmed" }
                        }
                    }
                }
            }

        postLockAction()
        return true
    }

    fun getQueueSize(): Int = queue.size()

    fun getBufferSize(): Int = dependencyStateLock.withLock { bufferedTransactionCount }

    private fun getPendingDependencyCount(): Int = dependencyStateLock.withLock { electionDependencies.size }

    private fun removeCandidates(
        publicKeyHeight: PublicKeyHeight,
        eventHash: AttoHash,
        confirmedHash: AttoHash? = null,
    ): RemovedCandidates =
        dependencyStateLock.withLock {
            val hashes = candidateHashes.remove(publicKeyHeight)?.toMutableSet() ?: mutableSetOf()
            hashes.add(eventHash)
            val confirmedTransactions = mutableSetOf<Transaction>()

            hashes.forEach { hash ->
                val transactions = electionDependencies.remove(hash).orEmpty()
                bufferedTransactionCount -= transactions.size
                if (hash == confirmedHash) {
                    confirmedTransactions.addAll(transactions)
                }
            }

            RemovedCandidates(hashes, confirmedTransactions)
        }

    override fun clear() {
        queue.clear()
        dependencyStateLock.withLock {
            electionDependencies.clear()
            candidateHashes.clear()
            bufferedTransactionCount = 0
        }
        duplicateDetector.clear()
    }

    private data class RemovedCandidates(
        val candidateHashes: Set<AttoHash>,
        val bufferedTransactions: Set<Transaction>,
    )
}
