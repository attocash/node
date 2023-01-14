package org.atto.node.transaction

import kotlinx.coroutines.flow.Flow
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.atto.node.AttoRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository


interface TransactionRepository : CoroutineCrudRepository<Transaction, AttoHash>, AttoRepository {

    suspend fun findFirstBy(): Transaction?

    suspend fun findFirstByPublicKey(publicKey: AttoPublicKey): Transaction?

    @Query(
        "SELECT * FROM Transaction t " +
                "WHERE t.public_key = :publicKey " +
                "ORDER BY t.height DESC " +
                "LIMIT 1"
    )
    suspend fun findLastByPublicKey(publicKey: AttoPublicKey): Transaction?

    @Query("SELECT * FROM Transaction t WHERE t.public_key = :publicKey AND t.height >= :fromHeight")
    suspend fun find(publicKey: AttoPublicKey, fromHeight: ULong): Flow<Transaction>

}