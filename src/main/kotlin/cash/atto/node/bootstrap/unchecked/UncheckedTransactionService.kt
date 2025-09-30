package cash.atto.node.bootstrap.unchecked

import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.UncheckedTransactionSaved
import cash.atto.node.executeAfterCommit
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.annotation.Timed
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class UncheckedTransactionService(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val uncheckedTransactionInserter: UncheckedTransactionInserter,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Timed("unchecked_transactions_save", description = "Time taken to save an unchecked transaction")
    suspend fun save(uncheckedTransactions: Collection<UncheckedTransaction>) {
        uncheckedTransactionInserter.insert(uncheckedTransactions)
        executeAfterCommit {
            uncheckedTransactions.forEach {
                logger.debug { "Saved $it" }
                eventPublisher.publish(UncheckedTransactionSaved(it))
            }
        }
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun cleanUp() {
        val deletedCount = uncheckedTransactionRepository.deleteExistingInTransaction()
        if (deletedCount > 0) {
            logger.debug { "Deleted $deletedCount unchecked transactions" }
        }
    }
}
