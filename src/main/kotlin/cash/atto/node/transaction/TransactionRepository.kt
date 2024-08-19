package cash.atto.node.transaction

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.node.AttoRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.math.BigInteger

interface TransactionRepository :
    CoroutineCrudRepository<Transaction, AttoHash>,
    AttoRepository {
    @Query(
        """
            SELECT t.* FROM transaction t
            WHERE t.public_key = :publicKey
            ORDER BY t.height ASC
            LIMIT 1
        """,
    )
    suspend fun findFirstByPublicKey(publicKey: AttoPublicKey): Transaction?

    @Query(
        """
            SELECT t.* from transaction t
            JOIN account a on t.hash = a.last_transaction_hash
            ORDER BY RAND()
            LIMIT :limit
        """,
    )
    suspend fun getLastSample(limit: Long): Flow<Transaction>

    @Query("SELECT * FROM transaction t WHERE t.public_key = :publicKey AND t.height BETWEEN :fromHeight and :toHeight ORDER BY height ASC")
    suspend fun findAsc(
        publicKey: AttoPublicKey,
        fromHeight: BigInteger,
        toHeight: BigInteger,
    ): Flow<Transaction>

    @Query(
        "SELECT * FROM transaction t WHERE t.public_key = :publicKey AND t.height BETWEEN :fromHeight and :toHeight ORDER BY height DESC",
    )
    suspend fun findDesc(
        publicKey: AttoPublicKey,
        fromHeight: AttoHeight,
        toHeight: AttoHeight,
    ): Flow<Transaction>
}
