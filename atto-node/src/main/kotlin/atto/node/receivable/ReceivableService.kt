package atto.node.receivable

import cash.atto.commons.AttoHash
import mu.KotlinLogging
import org.springframework.stereotype.Service

@Service
class ReceivableService(private val receivableRepository: ReceivableRepository) {
    private val logger = KotlinLogging.logger {}

    suspend fun save(receivable: Receivable) {
        receivableRepository.save(receivable)
        logger.debug { "Saved $receivable" }
    }

    suspend fun delete(hash: AttoHash) {
        val receivable = receivableRepository.findById(hash)!!
        receivableRepository.delete(receivable)
        logger.debug { "Deleted receivable $hash" }
    }
}