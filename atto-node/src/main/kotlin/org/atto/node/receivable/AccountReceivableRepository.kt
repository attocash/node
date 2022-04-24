package org.atto.node.receivable

import org.atto.commons.AttoHash
import org.atto.node.AttoRepository
import org.springframework.data.repository.Repository


interface AccountReceivableRepository : Repository<AttoHash, AccountReceivable>, AttoRepository {

    suspend fun save(receivable: AccountReceivable)

    suspend fun findByHash(hash: AttoHash): AccountReceivable?

    suspend fun delete(hash: AttoHash)

}