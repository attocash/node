package cash.atto.node.vote.weight

import cash.atto.commons.AttoPublicKey
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.LocalDateTime

interface WeightRepository : CoroutineCrudRepository<Weight, AttoPublicKey> {
    @Modifying
    @Query(
        """
        INSERT INTO weight (representative_algorithm, representative_public_key, weight, last_vote_timestamp)
        SELECT
          a.representative_algorithm,
          a.representative_public_key,
          CAST(SUM(a.balance) AS UNSIGNED) AS weight,
          COALESCE(v.last_vote_timestamp, '1970-01-01 00:00:00.000000') AS last_vote_timestamp
        FROM account a
        LEFT JOIN (
          SELECT public_key, MAX(received_at) AS last_vote_timestamp
          FROM vote
          GROUP BY public_key
        ) v ON v.public_key = a.representative_public_key
        GROUP BY a.representative_algorithm, a.representative_public_key, v.last_vote_timestamp
        ON DUPLICATE KEY UPDATE
          weight = VALUES(weight),
          last_vote_timestamp = GREATEST(weight.last_vote_timestamp, VALUES(last_vote_timestamp))
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
          AND last_vote_timestamp < :timestamp
        """,
    )
    suspend fun recordLastVoteTimestamp(
        publicKey: AttoPublicKey,
        timestamp: LocalDateTime,
    )
}
