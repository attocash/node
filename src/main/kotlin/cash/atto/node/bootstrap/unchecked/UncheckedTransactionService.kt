package cash.atto.node.bootstrap.unchecked

import cash.atto.node.EventPublisher
import cash.atto.node.bootstrap.UncheckedTransactionSaved
import cash.atto.node.executeAfterCommit
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.toSet
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Service
class UncheckedTransactionService(
    private val uncheckedTransactionRepository: UncheckedTransactionRepository,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional(isolation = Isolation.SERIALIZABLE)
    suspend fun save(uncheckedTransactions: Collection<UncheckedTransaction>) {
        val existingTransactions =
            uncheckedTransactionRepository
                .findAllById(uncheckedTransactions.map { it.id })
                .map { it.hash }
                .toSet()

        val transactionsToSave = uncheckedTransactions.filter { it.hash !in existingTransactions }

        val savedTransactions = uncheckedTransactionRepository.saveAll(transactionsToSave).toList()
        executeAfterCommit {
            savedTransactions.forEach {
                logger.debug { "Saved $it" }
                eventPublisher.publish(UncheckedTransactionSaved(it))
            }
        }
    }

    @Transactional(isolation = Isolation.SERIALIZABLE)
    suspend fun cleanUp() {
        val deletedCount = uncheckedTransactionRepository.deleteExistingInTransaction()
        logger.debug { "Deleted $deletedCount unchecked transactions" }
    }
}
