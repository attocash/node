package atto.node.account

import mu.KotlinLogging
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
