package cash.atto.node.receivable

import cash.atto.commons.AttoHash
import cash.atto.node.EventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ReceivableService(
    private val receivableRepository: ReceivableRepository,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    suspend fun save(receivable: Receivable) {
        receivableRepository.save(receivable)
        logger.debug { "Saved $receivable" }
        eventPublisher.publishAfterCommit(ReceivableSaved(receivable))
    }

    @Transactional
    suspend fun delete(hash: AttoHash) {
        val deleted = receivableRepository.delete(hash)
        require(deleted > 0) { "Receivable does not exist." }
        logger.debug { "Deleted receivable $hash" }
    }
}
