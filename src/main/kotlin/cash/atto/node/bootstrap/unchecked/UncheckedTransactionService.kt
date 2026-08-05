package cash.atto.node.bootstrap.unchecked

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.annotation.Timed
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class UncheckedTransactionService(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val uncheckedTransactionInserter: UncheckedTransactionInserter,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional(isolation = Isolation.READ_COMMITTED)
    @Timed("unchecked_transactions_save", description = "Time taken to save an unchecked transaction")
    suspend fun save(uncheckedTransactions: Collection<UncheckedTransaction>): Long {
        val affectedRows = uncheckedTransactionInserter.insert(uncheckedTransactions)
        logger.debug { "Saved ${uncheckedTransactions.size} unchecked transactions" }
        return affectedRows
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun cleanUp(limit: Long): Int {
        val deletedCount = uncheckedTransactionRepository.deleteExistingInTransaction(limit)
        if (deletedCount > 0) {
            logger.debug { "Deleted $deletedCount unchecked transactions" }
        }
        return deletedCount
    }
}
