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
            SELECT ut.serialized
            FROM (
                SELECT candidate.hash, candidate.timestamp, candidate.public_key, candidate.height
                FROM unchecked_transaction candidate
                LEFT JOIN account a
                  ON a.public_key = candidate.public_key
                WHERE candidate.height > COALESCE(a.height, 0)
                ORDER BY candidate.timestamp, candidate.public_key, candidate.height
                LIMIT :limit
            ) oldest
            JOIN unchecked_transaction ut
              ON ut.hash = oldest.hash
            ORDER BY oldest.timestamp, oldest.public_key, oldest.height
        """,
    )
    suspend fun findTopOldest(limit: Long): Flow<UncheckedTransaction>

    @Query(
        """
            SELECT EXISTS (
                SELECT 1
                FROM unchecked_transaction
                WHERE public_key = :publicKey
                  AND height > :height
            )
        """,
    )
    // MySQL exposes EXISTS as BIGINT, which asyncer R2DBC cannot decode directly as Boolean.
    suspend fun hasHigherTransaction(
        publicKey: AttoPublicKey,
        height: AttoHeight,
    ): Long

    @Query(
        """
            WITH first_gaps AS (
                SELECT
                    candidate.public_key,
                    MIN(candidate.height) AS height,
                    COALESCE(a.height, 0) AS account_height
                FROM (
                    SELECT DISTINCT public_key
                    FROM unchecked_transaction
                ) account_keys
                LEFT JOIN account a
                  ON a.public_key = account_keys.public_key
                JOIN unchecked_transaction candidate
                  ON candidate.public_key = account_keys.public_key
                 AND candidate.height > COALESCE(a.height, 0) + 1
                LEFT JOIN unchecked_transaction immediate_previous
                  ON immediate_previous.public_key = candidate.public_key
                 AND immediate_previous.height = candidate.height - 1
                WHERE immediate_previous.hash IS NULL
                GROUP BY candidate.public_key, COALESCE(a.height, 0)
            )
            SELECT
                boundary.public_key,
                COALESCE(
                    (
                        SELECT previous_transaction.height
                        FROM unchecked_transaction previous_transaction
                        WHERE previous_transaction.public_key = boundary.public_key
                          AND previous_transaction.height > first_gaps.account_height
                          AND previous_transaction.height < boundary.height
                        ORDER BY previous_transaction.height DESC
                        LIMIT 1
                    ),
                    first_gaps.account_height
                ) + 1 AS start_height,
                boundary.height - 1 AS end_height,
                boundary.previous AS expected_end_hash
            FROM first_gaps
            JOIN unchecked_transaction boundary
              ON boundary.public_key = first_gaps.public_key
             AND boundary.height = first_gaps.height
            ORDER BY boundary.timestamp, boundary.public_key, boundary.height
            LIMIT :limit
        """,
    )
    suspend fun findGaps(limit: Int): Flow<GapView>

    @Modifying
    @Query(
        """
            DELETE FROM unchecked_transaction
            WHERE EXISTS (
                SELECT 1
                FROM account a
                WHERE a.public_key = unchecked_transaction.public_key
                  AND unchecked_transaction.height <= a.height
            )
            LIMIT :limit
        """,
    )
    suspend fun deleteExistingInTransaction(limit: Long): Int
}

data class GapView(
    val publicKey: AttoPublicKey,
    val startHeight: AttoHeight,
    val endHeight: AttoHeight,
    val expectedEndHash: AttoHash,
)
