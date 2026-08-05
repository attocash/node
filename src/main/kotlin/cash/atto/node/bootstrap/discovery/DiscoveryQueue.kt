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
    private val pressureMonitor: DiscoveryPressureMonitor,
) {
    private val logger = KotlinLogging.logger {}
    private val duplicateDetector = DuplicateDetector<AttoHash>(10.minutes)
    private val outstanding = AtomicInteger()
    private val inFlight = AtomicInteger()
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

        outstanding.incrementAndGet()
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

    fun isAtCapacity(): Boolean = outstanding.get() >= getTargetCapacity()

    fun remainingTargetCapacity(): Int = maxOf(0, getTargetCapacity() - outstanding.get())

    internal fun takeBatch(): List<PendingDiscovery> =
        buildList(properties.batchSize) {
            while (size < properties.batchSize) {
                val discovery = buffer.tryReceive().getOrNull() ?: break
                inFlight.incrementAndGet()
                metrics.dequeued(Duration.between(discovery.enqueuedAt, clock.instant()))
                add(discovery)
            }
        }

    internal fun acknowledge(batch: List<PendingDiscovery>) {
        batch.forEach { discovery ->
            duplicateDetector.refresh(discovery.transaction.hash)
        }
        releaseInFlight(batch.size)
    }

    internal fun reset(retryBatch: List<PendingDiscovery>) {
        releaseInFlight(retryBatch.size)
        while (true) {
            buffer.tryReceive().getOrNull() ?: break
            check(outstanding.decrementAndGet() >= 0) {
                "Discovery outstanding count became negative"
            }
        }
        duplicateDetector.clear()
    }

    internal fun getBacklogDepth(): Int = outstanding.get()

    internal fun getInFlightCount(): Int = inFlight.get()

    internal fun getTargetCapacity(): Int = pressureMonitor.targetCapacity(properties.capacity)

    internal fun getBacklogOvershoot(): Int = maxOf(0, outstanding.get() - getTargetCapacity())

    internal fun isPhysicalBufferFull(): Boolean = outstanding.get() - inFlight.get() >= properties.capacity + properties.headroom

    private fun discard(discovery: PendingDiscovery) {
        check(outstanding.decrementAndGet() >= 0) {
            "Discovery outstanding count became negative"
        }
        duplicateDetector.remove(discovery.transaction.hash)
    }

    private fun releaseInFlight(count: Int) {
        if (count == 0) {
            return
        }

        check(inFlight.addAndGet(-count) >= 0) {
            "Discovery in-flight count became negative"
        }
        check(outstanding.addAndGet(-count) >= 0) {
            "Discovery outstanding count became negative"
        }
    }
}
