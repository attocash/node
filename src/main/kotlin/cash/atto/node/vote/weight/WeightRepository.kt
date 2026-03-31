package cash.atto.node.vote.weight

import cash.atto.commons.AttoPublicKey
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface WeightRepository : CoroutineCrudRepository<Weight, AttoPublicKey> {
    @Modifying
    @Query(
        """
        INSERT INTO weight (representative_algorithm, representative_public_key, weight)
        SELECT a.representative_algorithm, a.representative_public_key, CAST(SUM(a.balance) AS UNSIGNED) AS weight
        FROM account a
        LEFT JOIN weight w ON a.representative_public_key = w.representative_public_key
        GROUP BY a.representative_algorithm, a.representative_public_key
        ON DUPLICATE KEY UPDATE weight = VALUES(weight)
        """,
    )
    suspend fun upsertWeights()

    @Modifying
    @Query(
        """
        DELETE w FROM weight w
        LEFT JOIN account a ON w.representative_public_key = a.representative_public_key
        WHERE a.representative_public_key IS NULL OR w.weight = 0
        """,
    )
    suspend fun deleteStale()

    @Modifying
    @Query(
        """
        UPDATE weight
        SET last_vote_timestamp = :timestamp
        WHERE representative_public_key = :publicKey
          AND (last_vote_timestamp IS NULL OR last_vote_timestamp < :timestamp)
        """,
    )
    suspend fun updateLastVoteTimestamp(
        publicKey: AttoPublicKey,
        timestamp: Instant,
    )
}
