package cash.atto.node.vote

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoSignature
import cash.atto.node.AttoRepository
import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface VoteRepository :
    CoroutineCrudRepository<Vote, AttoSignature>,
    AttoRepository {
    @Query(
        """
        SELECT * FROM (
            SELECT *, ROW_NUMBER() OVER(PARTITION BY hash, public_key ORDER BY timestamp DESC) as num
            FROM vote v
            WHERE timestamp > :timestamp
        ) TEMP
        WHERE num = 1
        """,
    )
    suspend fun findLatestAfter(timestamp: Instant): List<Vote>

    @Query(
        """
        SELECT *
        FROM vote v
        WHERE v.hash = :hash
        ORDER BY v.weight DESC
        """,
    )
    suspend fun findByHash(hash: AttoHash): Flow<Vote>
}
