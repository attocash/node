package org.atto.node.transaction

import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.AttoRepository
import org.springframework.data.repository.Repository


interface TransactionRepository : Repository<AttoHash, Transaction>, AttoRepository {

    suspend fun save(account: Transaction)

    suspend fun findByHash(hash: AttoHash): Transaction?

    suspend fun findFirst(): Transaction?

    suspend fun findFirstByPublicKey(publicKey: AttoPublicKey): Transaction?

}