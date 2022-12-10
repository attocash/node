package org.atto.node.transaction

import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.AttoRepository
import org.springframework.data.repository.kotlin.CoroutineCrudRepository


interface TransactionRepository : CoroutineCrudRepository<Transaction, AttoHash>, AttoRepository {

    suspend fun findFirstBy(): Transaction?

    suspend fun findFirstByPublicKey(publicKey: AttoPublicKey): Transaction?

}