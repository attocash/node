package org.atto.node.vote

import org.atto.commons.AttoSignature
import org.atto.node.AttoRepository
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

}