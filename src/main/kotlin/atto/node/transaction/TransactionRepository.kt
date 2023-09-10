package atto.node.transaction

import atto.node.AttoRepository
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository


interface TransactionRepository : CoroutineCrudRepository<Transaction, AttoHash>, AttoRepository {

    suspend fun findFirstByPublicKey(publicKey: AttoPublicKey): Transaction?

    @Query(
        """
            SELECT t.* from transaction t
            JOIN account a on t.hash = a.last_transaction_hash
            ORDER BY RAND()
            LIMIT :limit
        """
    )
    suspend fun getLastSample(limit: Long): Flow<Transaction>

    @Query("SELECT * FROM transaction t WHERE t.public_key = :publicKey AND t.height >= :fromHeight ORDER BY height ASC")
    suspend fun findAsc(publicKey: AttoPublicKey, fromHeight: ULong): Flow<Transaction>

    @Query("SELECT * FROM transaction t WHERE t.public_key = :publicKey AND t.height BETWEEN :fromHeight and :toHeight ORDER BY height DESC")
    suspend fun findDesc(
        publicKey: AttoPublicKey,
        fromHeight: ULong,
        toHeight: ULong
    ): Flow<Transaction>

}