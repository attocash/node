package atto.node.vote

import atto.node.AttoRepository
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoSignature
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant


interface VoteRepository : CoroutineCrudRepository<Vote, AttoSignature>, AttoRepository {

    @Query(
        """
        SELECT * FROM (
            SELECT *, ROW_NUMBER() OVER(PARTITION BY hash, public_key ORDER BY timestamp DESC) as num
            FROM Vote v 
            WHERE timestamp > :timestamp
        ) TEMP
        WHERE num = 1
        """
    )
    suspend fun findLatestAfter(timestamp: Instant): List<Vote>

    @Query(
        """
        SELECT * 
        FROM Vote v 
        WHERE v.hash = :hash
        ORDER BY v.weight DESC
        LIMIT :limit
        """
    )
    suspend fun findByHash(hash: AttoHash, limit: Long): List<Vote>
}