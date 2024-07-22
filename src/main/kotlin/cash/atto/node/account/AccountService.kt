package cash.atto.node.account

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

@Service
class AccountService(
    private val accountRepository: AccountRepository,
) {
    private val logger = KotlinLogging.logger {}

    suspend fun save(account: Account) {
        accountRepository.save(account)
        logger.debug { "Saved $account" }
    }
}
