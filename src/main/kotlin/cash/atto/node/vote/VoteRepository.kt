package cash.atto.node.vote

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.node.AttoRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.math.BigInteger
import java.time.Instant

interface VoteRepository :
    CoroutineCrudRepository<Vote, AttoHash>,
    AttoRepository {
    @Query(
        """
        SELECT *, w.weight FROM (
            SELECT *, ROW_NUMBER() OVER(PARTITION BY public_key ORDER BY received_at DESC) as num
            FROM vote v
            WHERE received_at > :receivedAt
        ) temp
        JOIN weight w on temp.public_key = w.representative_public_key
        WHERE num = 1
        """,
    )
    suspend fun findLatestAfter(receivedAt: Instant): List<Vote>

    @Query(
        """
        SELECT v.*, w.weight
        FROM vote v
        JOIN weight w on v.public_key = w.representative_public_key
        WHERE v.block_hash = :blockHash
        ORDER BY w.weight DESC
        """,
    )
    suspend fun findByBlockHash(blockHash: AttoHash): Flow<Vote>

    @Query(
        """
            DELETE FROM vote
            WHERE block_hash NOT IN (
                SELECT last_transaction_hash
                FROM account
            );
        """,
    )
    fun deleteOld(): Int


    @Query(
        """
            WITH unconfirmed AS (
                SELECT a.last_transaction_hash
                FROM account a
                         LEFT JOIN vote v ON a.last_transaction_hash = v.block_hash
                         LEFT JOIN weight w ON v.public_key = w.representative_public_key
                GROUP BY a.last_transaction_hash
                HAVING COALESCE(SUM(w.weight), 0) < :onlineWeight
                LIMIT 1000
            ),
                 missing_reps AS (
                     SELECT
                         unconfirmed.last_transaction_hash,
                         w.representative_public_key,
                         w.weight,
                         ROW_NUMBER() OVER (
                             PARTITION BY unconfirmed.last_transaction_hash
                             ORDER BY w.weight DESC
                             ) AS rk
                     FROM unconfirmed
                              CROSS JOIN weight w
                              LEFT JOIN vote v ON v.public_key = w.representative_public_key AND v.block_hash = unconfirmed.last_transaction_hash
                     WHERE v.block_hash IS NULL
                 )
            SELECT last_transaction_hash, representative_public_key
            FROM missing_reps
            WHERE rk between 1 and 2;
        """
    )
    suspend fun findMissingVote(onlineWeight: BigInteger): List<MissingVote>
}

data class MissingVote(val lastTransactionHash: AttoHash, val representativePublicKey: AttoPublicKey)
