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
            SELECT ut.* FROM unchecked_transaction ut
            JOIN account a ON a.public_key = ut.public_key AND ut.height > a.height
            ORDER BY timestamp
            LIMIT :limit
        """,
    )
    suspend fun findTopOldest(limit: Long): Flow<UncheckedTransaction>

    @Query(
        """
            WITH max_transaction_height AS (
                SELECT public_key, COALESCE(MAX(height), 0) AS max_height
                FROM transaction
                GROUP BY public_key
            ),
            calculated_gaps AS (
                SELECT
                    ut.public_key,
                    GREATEST(
                        COALESCE(mth.max_height, 0),
                        COALESCE(LAG(ut.height) OVER (PARTITION BY ut.public_key ORDER BY ut.height ASC), 0)
                    ) + 1 AS start_height,
                    ut.height - 1 AS end_height,
                    ut.previous AS expected_end_hash,
                    ut.timestamp AS transaction_timestamp
                FROM unchecked_transaction ut
                LEFT JOIN max_transaction_height mth
                    ON ut.public_key = mth.public_key
            )
            SELECT public_key, start_height, end_height, expected_end_hash
            FROM calculated_gaps
            WHERE start_height <= end_height
            AND public_key not in (:publicKeyToExclude)
            ORDER BY transaction_timestamp
            LIMIT :limit;
        """,
    )
    suspend fun findGaps(
        publicKeyToExclude: Collection<AttoPublicKey>,
        limit: Long,
    ): Flow<GapView>

    @Modifying
    @Query(
        """
            DELETE ut
            FROM unchecked_transaction ut
            JOIN account a
              ON a.public_key = ut.public_key
             AND ut.height <= a.height
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
