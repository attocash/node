package cash.atto.node.bootstrap.discovery

import cash.atto.node.CacheSupport
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit

@Component
class DiscoveryPersistenceWorker(
    private val uncheckedTransactionService: UncheckedTransactionService,
    private val queue: DiscoveryQueue,
    private val properties: DiscoveryProperties,
    private val metrics: DiscoveryMetrics,
    private val clock: Clock,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}
    private val flushMutex = Mutex()

    private var nextRetryAt = Instant.EPOCH
    private var retryBackoffInSeconds = properties.retryInitialBackoffInSeconds
    private var retryBatch: List<PendingDiscovery>? = null

    @Scheduled(fixedRate = 1, timeUnit = TimeUnit.SECONDS)
    suspend fun flush() {
        if (!flushMutex.tryLock()) {
            return
        }

        try {
            if (clock.instant().isBefore(nextRetryAt)) {
                return
            }

            while (true) {
                val batch = retryBatch ?: queue.takeBatch()
                if (batch.isEmpty()) {
                    return
                }
                retryBatch = batch

                val sample = metrics.startBatch(batch.size)
                val affectedRows =
                    try {
                        uncheckedTransactionService.save(batch.map { it.transaction })
                    } catch (e: CancellationException) {
                        metrics.stopBatch(sample)
                        throw e
                    } catch (e: Exception) {
                        metrics.stopBatch(sample)
                        metrics.persistenceFailed()
                        scheduleRetry()
                        logger.warn(e) {
                            "Failed to save ${batch.size} unchecked transactions. Retrying the same batch after " +
                                "${Duration.between(clock.instant(), nextRetryAt)}"
                        }
                        return
                    }

                val elapsed = metrics.stopBatch(sample)
                queue.acknowledge(batch)
                retryBatch = null
                metrics.persisted(batch)
                metrics.affectedRows(affectedRows)
                resetRetry()
                logger.info { "Saved ${batch.size} unchecked transactions in $elapsed" }
            }
        } finally {
            flushMutex.unlock()
        }
    }

    override fun clear() {
        runBlocking {
            flushMutex.withLock {
                retryBatch = null
                queue.reset()
                resetRetry()
            }
        }
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
