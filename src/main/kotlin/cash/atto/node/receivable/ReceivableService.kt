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
    suspend fun saveAll(receivables: List<Receivable>) {
        receivableRepository.saveAll(receivables).collect { receivable ->
            logger.debug { "Saved $receivable" }
            eventPublisher.publishAfterCommit(ReceivableSaved(receivable))
        }
    }

    @Transactional
    suspend fun deleteAll(hashes: List<AttoHash>) {
        if (hashes.isEmpty()) return
        val deleted = receivableRepository.deleteAllByHash(hashes)
        require(deleted == hashes.size) { "One or more receivable does not exist. Hashes: $hashes" }
        logger.debug { "Deleted receivable $hashes" }
    }
}
