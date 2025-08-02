package cash.atto.node.bootstrap.unchecked

import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.UncheckedTransactionSaved
import cash.atto.node.executeAfterCommit
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UncheckedTransactionService(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    private val mutex = Mutex()

    @Transactional
    suspend fun save(uncheckedTransactions: Collection<UncheckedTransaction>) {
        mutex.withLock {
            val savedTransactions = uncheckedTransactionRepository.saveAll(uncheckedTransactions).toList()
            executeAfterCommit {
                savedTransactions.forEach {
                    logger.debug { "Saved $it" }
                    eventPublisher.publish(UncheckedTransactionSaved(it))
                }
            }
        }
    }

    @Transactional
    suspend fun cleanUp() {
        mutex.withLock {
            val deletedCount = uncheckedTransactionRepository.deleteExistingInTransaction()
            logger.debug { "Deleted $deletedCount unchecked transactions" }
        }
    }
}
