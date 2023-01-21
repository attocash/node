package org.atto.node.bootstrap.unchecked;

import mu.KotlinLogging
import org.atto.commons.AttoHash
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class UncheckedTransactionService(val uncheckedTransactionRepository: UncheckedTransactionRepository) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    suspend fun save(uncheckedTransaction: UncheckedTransaction) {
        try {
            uncheckedTransactionRepository.save(uncheckedTransaction)
            logger.debug { "Saved $uncheckedTransaction" }
        } catch (e: DuplicateKeyException) {
            logger.debug(e) { "Already exist $uncheckedTransaction" }
        }
    }

    @Transactional
    suspend fun delete(hash: AttoHash) {
        uncheckedTransactionRepository.deleteById(hash)

        logger.debug { "Deleted $hash" }
    }
}
