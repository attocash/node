package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.node.AttoRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface UncheckedTransactionRepository :
    CoroutineCrudRepository<UncheckedTransaction, AttoHash>,
    AttoRepository {
    @Query(
        """
            SELECT * FROM unchecked_transaction
            ORDER BY timestamp
            LIMIT :limit
        """,
    )
    suspend fun findTopOldest(limit: Long): Flow<UncheckedTransaction>

    @Modifying
    @Query(
        """
            WITH hashes_to_delete AS (
                SELECT ut.hash
                FROM unchecked_transaction ut
                JOIN account a ON ut.public_key = a.public_key
                WHERE ut.height <= a.height
            )
            DELETE FROM unchecked_transaction
            WHERE hash IN (SELECT hash FROM hashes_to_delete);
        """,
    )
    suspend fun deleteExistingInTransaction(): Int

    @Query("SELECT COUNT(*) FROM unchecked_transaction")
    suspend fun countAll(): Long
}

data class GapView(
    val publicKey: AttoPublicKey,
    val startHeight: AttoHeight,
    val endHeight: AttoHeight,
    val expectedEndHash: AttoHash,
)
