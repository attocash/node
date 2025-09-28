package cash.atto.node.bootstrap.unchecked

import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.UncheckedTransactionSaved
import cash.atto.node.executeAfterCommit
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UncheckedTransactionService(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val uncheckedTransactionInserter: UncheckedTransactionInserter,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    suspend fun save(uncheckedTransactions: Collection<UncheckedTransaction>) {
        uncheckedTransactionInserter.insert(uncheckedTransactions)
        executeAfterCommit {
            uncheckedTransactions.forEach {
                logger.debug { "Saved $it" }
                eventPublisher.publish(UncheckedTransactionSaved(it))
            }
        }
    }

    @Transactional
    suspend fun cleanUp() {
        val deletedCount = uncheckedTransactionRepository.deleteExistingInTransaction()
        logger.debug { "Deleted $deletedCount unchecked transactions" }
    }
}
