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
                SELECT ut.*
                FROM unchecked_transaction AS ut
                LEFT JOIN account AS a
                  ON a.public_key = ut.public_key
                WHERE ut.height > COALESCE(a.height, 0)
                ORDER BY ut.timestamp
                LIMIT :limit;
        """,
    )
    suspend fun findTopOldest(limit: Long): Flow<UncheckedTransaction>

    @Query(
        """
            WITH calculated_gaps AS (
                SELECT
                    ut.public_key,
                    GREATEST(
                        COALESCE(a.height, 0),
                        COALESCE(LAG(ut.height) OVER (PARTITION BY ut.public_key ORDER BY ut.height ASC), 0)
                    ) + 1 AS start_height,
                    ut.height - 1 AS end_height,
                    ut.previous AS expected_end_hash,
                    ut.timestamp AS transaction_timestamp
                FROM unchecked_transaction ut
                LEFT JOIN account a ON a.public_key = ut.public_key
                WHERE ut.height > COALESCE(a.height, 0)
                AND ut.public_key NOT IN (:publicKeyToExclude)

            ),
            ranked AS (
              SELECT
                cg.*,
                ROW_NUMBER() OVER (
                  PARTITION BY cg.public_key
                  ORDER BY cg.transaction_timestamp ASC
                ) AS rn
              FROM calculated_gaps cg
              WHERE cg.start_height <= cg.end_height
            )
            SELECT public_key, start_height, end_height, expected_end_hash
            FROM ranked
            WHERE rn = 1
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
}

data class GapView(
    val publicKey: AttoPublicKey,
    val startHeight: AttoHeight,
    val endHeight: AttoHeight,
    val expectedEndHash: AttoHash,
)
