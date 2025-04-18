package cash.atto.node.account.entry

import cash.atto.node.EventPublisher
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AccountEntryService(
    private val repository: AccountEntryRepository,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    suspend fun saveAll(entries: List<AccountEntry>) {
        repository.saveAll(entries).collect { entry ->
            logger.debug { "Saved $entry" }
            eventPublisher.publishAfterCommit(AccountEntrySaved(entry))
        }
    }
}
