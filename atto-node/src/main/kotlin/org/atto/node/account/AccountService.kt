package org.atto.node.account

import org.springframework.stereotype.Service

@Service
class AccountService(private val accountRepository: AccountRepository) {

    suspend fun save(account: Account) {
        accountRepository.save(account)
    }
}