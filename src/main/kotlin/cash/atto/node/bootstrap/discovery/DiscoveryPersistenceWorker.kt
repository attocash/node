package cash.atto.node.bootstrap.discovery

import cash.atto.node.CacheSupport
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionService
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component

@Component
class DiscoveryPersistenceWorker(
    private val uncheckedTransactionService: UncheckedTransactionService,
    private val queue: DiscoveryQueue,
    private val metrics: DiscoveryMetrics,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}
    private val flushMutex = Mutex()

    @Volatile
    private var retryBatch: List<PendingDiscovery>? = null

    suspend fun persist(): Int =
        flushMutex.withLock {
            val batch = retryBatch ?: queue.takeBatch()
            if (batch.isEmpty()) {
                return@withLock 0
            }
            persist(batch)
        }

    override fun clear() {
        runBlocking {
            flushMutex.withLock {
                retryBatch = null
                queue.reset()
            }
        }
    }

    private suspend fun persist(discoveries: List<PendingDiscovery>): Int {
        val sample = metrics.startBatch(discoveries.size)
        val affectedRows =
            try {
                uncheckedTransactionService.save(discoveries.map { it.transaction })
            } catch (e: Exception) {
                metrics.stopBatch(sample)
                if (e !is CancellationException) {
                    retryBatch = discoveries
                    metrics.persistenceFailed()
                }
                throw e
            }

        val elapsed = metrics.stopBatch(sample)
        retryBatch = null
        metrics.persisted(discoveries)
        metrics.affectedRows(affectedRows)
        logger.info { "Saved ${discoveries.size} unchecked transactions in $elapsed" }
        return discoveries.size
    }
}
