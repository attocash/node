package org.atto.node.receivable

import org.atto.commons.AttoHash
import org.springframework.stereotype.Service

@Service
class AccountReceivableService(private val accountReceivableRepository: AccountReceivableRepository) {

    suspend fun save(receivable: AccountReceivable) {
        accountReceivableRepository.save(receivable)
    }

    suspend fun delete(hash: AttoHash) {
        accountReceivableRepository.delete(hash)
    }
}