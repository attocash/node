package cash.atto.node.bootstrap.discovery

import cash.atto.node.CacheSupport
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import cash.atto.node.bootstrap.unchecked.UncheckedWorkTracker
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

@Component
class DiscoveryPersistenceWorker(
    private val uncheckedTransactionService: UncheckedTransactionService,
    private val queue: DiscoveryQueue,
    private val properties: DiscoveryProperties,
    private val metrics: DiscoveryMetrics,
    private val clock: Clock,
    private val workTracker: UncheckedWorkTracker,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}
    private val flushMutex = Mutex()

    @Volatile
    private var nextRetryAt = Instant.EPOCH

    private var retryBackoffInSeconds = properties.retryInitialBackoffInSeconds

    @Volatile
    private var retryBatch: List<PendingDiscovery>? = null

    internal fun isRetryWaiting(): Boolean = retryBatch != null && clock.instant().isBefore(nextRetryAt)

    internal fun hasRetryBatch(): Boolean = retryBatch != null

    suspend fun persistIfReady(): DiscoveryPersistenceResult {
        if (!flushMutex.tryLock()) {
            return DiscoveryPersistenceResult.Busy
        }

        try {
            if (clock.instant().isBefore(nextRetryAt)) {
                return DiscoveryPersistenceResult.RetryWaiting
            }

            val batch = retryBatch ?: queue.takeBatch()
            if (batch.isEmpty()) {
                return DiscoveryPersistenceResult.Idle
            }
            retryBatch = batch
            return persist(batch)
        } finally {
            flushMutex.unlock()
        }
    }

    override fun clear() {
        runBlocking {
            flushMutex.withLock {
                val discoveries = retryBatch.orEmpty()
                retryBatch = null
                queue.reset(discoveries)
                resetRetry()
            }
        }
    }

    private suspend fun persist(discoveries: List<PendingDiscovery>): DiscoveryPersistenceResult {
        val sample = metrics.startBatch(discoveries.size)
        val affectedRows =
            try {
                uncheckedTransactionService.save(discoveries.map { it.transaction })
            } catch (exception: CancellationException) {
                metrics.stopBatch(sample)
                throw exception
            } catch (exception: Exception) {
                metrics.stopBatch(sample)
                metrics.persistenceFailed()
                scheduleRetry()
                logger.warn(exception) {
                    "Failed to save ${discoveries.size} unchecked transactions. Retrying the same batch after " +
                        "${Duration.between(clock.instant(), nextRetryAt)}"
                }
                return DiscoveryPersistenceResult.Failed
            }

        val elapsed = metrics.stopBatch(sample)
        queue.acknowledge(discoveries)
        retryBatch = null
        metrics.persisted(discoveries)
        metrics.affectedRows(affectedRows)
        if (affectedRows > 0) {
            workTracker.markChanged()
        }
        resetRetry()
        logger.info { "Saved ${discoveries.size} unchecked transactions in $elapsed" }
        return DiscoveryPersistenceResult.Persisted
    }

    private fun scheduleRetry() {
        nextRetryAt = clock.instant().plusSeconds(retryBackoffInSeconds)
        retryBackoffInSeconds =
            if (retryBackoffInSeconds >= properties.retryMaxBackoffInSeconds / 2) {
                properties.retryMaxBackoffInSeconds
            } else {
                minOf(retryBackoffInSeconds * 2, properties.retryMaxBackoffInSeconds)
            }
    }

    private fun resetRetry() {
        nextRetryAt = Instant.EPOCH
        retryBackoffInSeconds = properties.retryInitialBackoffInSeconds
    }
}

sealed interface DiscoveryPersistenceResult {
    data object Idle : DiscoveryPersistenceResult

    data object Busy : DiscoveryPersistenceResult

    data object RetryWaiting : DiscoveryPersistenceResult

    data object Failed : DiscoveryPersistenceResult

    data object Persisted : DiscoveryPersistenceResult
}
