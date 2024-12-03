package cash.atto.node.bootstrap.unchecked

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UncheckedTransactionService(
    val uncheckedTransactionRepository: UncheckedTransactionRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    suspend fun save(uncheckedTransactions: Collection<UncheckedTransaction>) {
        // TODO: save as batch
        uncheckedTransactions.forEach {
            try {
                uncheckedTransactionRepository.save(it)
                logger.debug { "Saved $it" }
            } catch (e: DuplicateKeyException) {
                logger.debug { "Already exist $it" }
            }
        }
    }

    @Transactional
    suspend fun cleanUp() {
        val deletedCount = uncheckedTransactionRepository.deleteExistingInTransaction()
        logger.debug { "Deleted $deletedCount unchecked transactions" }
    }
}
