package cash.atto.node.vote

import cash.atto.commons.AttoHash
import cash.atto.node.AttoRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface VoteRepository :
    CoroutineCrudRepository<Vote, AttoHash>,
    AttoRepository {
    @Query(
        """
        SELECT * FROM (
            SELECT *, ROW_NUMBER() OVER(PARTITION BY public_key ORDER BY received_at DESC) as num
            FROM vote v
            WHERE received_at > :receivedAt
        ) TEMP
        WHERE num = 1
        """,
    )
    suspend fun findLatestAfter(receivedAt: Instant): List<Vote>

    @Query(
        """
        SELECT *
        FROM vote v
        WHERE v.block_hash = :blockHash
        ORDER BY v.weight DESC
        """,
    )
    suspend fun findByBlockHash(blockHash: AttoHash): Flow<Vote>
}
