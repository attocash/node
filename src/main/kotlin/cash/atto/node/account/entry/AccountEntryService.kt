package cash.atto.node.account.entry

import cash.atto.node.EventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

@Service
class AccountEntryService(
    private val repository: AccountEntryRepository,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun save(entry: AccountEntry) {
        repository.save(entry)
        logger.debug { "Saved $entry" }
        eventPublisher.publishAfterCommit(AccountEntrySaved(entry))
    }
}
