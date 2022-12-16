package org.atto.node.receivable

import mu.KotlinLogging
import org.atto.commons.AttoHash
import org.springframework.stereotype.Service

@Service
class AccountReceivableService(private val accountReceivableRepository: AccountReceivableRepository) {
    private val logger = KotlinLogging.logger {}

    suspend fun save(receivable: AccountReceivable) {
        accountReceivableRepository.save(receivable)
        logger.debug { "Saved $receivable" }
    }

    suspend fun delete(hash: AttoHash) {
        accountReceivableRepository.deleteById(hash)
        logger.debug { "Deleted receivable $hash" }
    }
}