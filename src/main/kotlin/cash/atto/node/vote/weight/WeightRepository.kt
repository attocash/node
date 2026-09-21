package cash.atto.node.vote.weight

import cash.atto.commons.AttoPublicKey
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.math.BigInteger
import java.time.LocalDateTime

interface WeightRepository : CoroutineCrudRepository<Weight, AttoPublicKey> {
    @Query(
        """
        SELECT
          a.representative_public_key,
          CAST(SUM(a.balance) AS UNSIGNED) AS weight,
          COALESCE(
            v.last_vote_timestamp,
            CAST('1970-01-01 00:00:00.000000' AS DATETIME(6))
          ) AS last_vote_timestamp
        FROM account a
        LEFT JOIN (
          SELECT public_key, MAX(received_at) AS last_vote_timestamp
          FROM vote
          GROUP BY public_key
        ) v ON v.public_key = a.representative_public_key
        GROUP BY a.representative_algorithm, a.representative_public_key, v.last_vote_timestamp
        HAVING SUM(a.balance) > 0
        ORDER BY a.representative_public_key
        """,
    )
    fun findCalculatedWeights(): Flow<Weight>

    @Modifying
    @Query(
        """
        INSERT INTO weight (representative_algorithm, representative_public_key, weight, last_vote_timestamp)
        VALUES ('V1', :publicKey, :weight, :lastVoteTimestamp)
        ON DUPLICATE KEY UPDATE
          weight = VALUES(weight),
          last_vote_timestamp = GREATEST(weight.last_vote_timestamp, VALUES(last_vote_timestamp))
        """,
    )
    suspend fun upsert(
        publicKey: AttoPublicKey,
        weight: BigInteger,
        lastVoteTimestamp: LocalDateTime,
    )

    @Modifying
    @Query("DELETE FROM weight WHERE representative_public_key NOT IN (:publicKeys) OR weight = 0")
    suspend fun deleteAllExcept(publicKeys: Collection<AttoPublicKey>)

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
