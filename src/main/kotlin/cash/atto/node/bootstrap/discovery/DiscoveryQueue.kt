package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoHash
import cash.atto.node.DuplicateDetector
import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.bootstrap.unchecked.toUncheckedTransaction
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.channels.Channel
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

@Component
class DiscoveryQueue(
    private val eventPublisher: EventPublisher,
    private val properties: DiscoveryProperties,
    private val metrics: DiscoveryMetrics,
    private val clock: Clock,
) {
    private val logger = KotlinLogging.logger {}
    private val duplicateDetector = DuplicateDetector<AttoHash>(10.minutes)
    private val queued = AtomicInteger()
    private val buffer =
        Channel<PendingDiscovery>(
            capacity = properties.capacity + properties.headroom,
            onUndeliveredElement = ::discard,
        )

    @PostConstruct
    fun start() {
        metrics.bind(this)
    }

    /**
     * Keeps an already-discovered transaction until it can enter the bounded
     * persistence buffer. New discovery should stop when [isAtCapacity] is true,
     * while replies already in flight are allowed to suspend here.
     */
    suspend fun queue(
        event: TransactionDiscovered,
        source: DiscoverySource,
    ): Boolean {
        val transaction = event.transaction.toUncheckedTransaction()
        val hash = transaction.hash
        val discovery =
            PendingDiscovery(
                transaction = transaction,
                source = source,
                enqueuedAt = clock.instant(),
            )

        if (duplicateDetector.isDuplicate(hash)) {
            return false
        }

        queued.incrementAndGet()
        buffer.send(discovery)

        metrics.admitted(source)
        try {
            eventPublisher.publish(event)
        } catch (e: Exception) {
            logger.error(e) {
                "Transaction $hash entered the persistence buffer, but its discovery event could not be published"
            }
        }
        return true
    }

    fun isAtCapacity(): Boolean = queued.get() >= properties.capacity

    fun remainingTargetCapacity(): Int = maxOf(0, properties.capacity - queued.get())

    internal fun takeBatch(): List<PendingDiscovery> =
        buildList(properties.batchSize) {
            while (size < properties.batchSize) {
                val discovery = buffer.tryReceive().getOrNull() ?: break
                check(queued.decrementAndGet() >= 0) {
                    "Discovery queue depth became negative"
                }
                metrics.dequeued(Duration.between(discovery.enqueuedAt, clock.instant()))
                add(discovery)
            }
        }

    internal fun acknowledge(batch: List<PendingDiscovery>) {
        batch.forEach { discovery ->
            duplicateDetector.refresh(discovery.transaction.hash)
        }
    }

    internal fun reset() {
        while (true) {
            buffer.tryReceive().getOrNull() ?: break
            queued.decrementAndGet()
        }
        duplicateDetector.clear()
    }

    internal fun getBacklogDepth(): Int = queued.get()

    internal fun getBacklogOvershoot(): Int = maxOf(0, queued.get() - properties.capacity)

    private fun discard(discovery: PendingDiscovery) {
        queued.decrementAndGet()
        duplicateDetector.remove(discovery.transaction.hash)
    }
}
