package org.atto.node.receivable

import mu.KotlinLogging
import org.atto.commons.AttoHash
import org.springframework.stereotype.Service

@Service
class ReceivableService(private val receivableRepository: ReceivableRepository) {
    private val logger = KotlinLogging.logger {}

    suspend fun save(receivable: Receivable) {
        receivableRepository.save(receivable)
        logger.debug { "Saved $receivable" }
    }

    suspend fun delete(hash: AttoHash) {
        receivableRepository.deleteById(hash)
        logger.debug { "Deleted receivable $hash" }
    }
}