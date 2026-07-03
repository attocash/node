package cash.atto.node.vote

import cash.atto.commons.AttoHash
import cash.atto.node.AttoRepository
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface StaleVoteBlockRepository :
    CoroutineCrudRepository<StaleVoteBlock, AttoHash>,
    StaleVoteBlockBulkRepository,
    AttoRepository {
    @Query(
        """
            INSERT IGNORE INTO stale_vote_block (block_hash)
            SELECT DISTINCT v.block_hash
            FROM vote v
                     LEFT JOIN account a ON a.last_transaction_hash = v.block_hash
            WHERE a.last_transaction_hash IS NULL
              AND v.received_at < :receivedBefore
        """,
    )
    suspend fun reconcileOld(receivedBefore: Instant): Int

    @Query(
        """
            DELETE s
            FROM stale_vote_block s
                     LEFT JOIN vote v ON v.block_hash = s.block_hash
            WHERE v.block_hash IS NULL
              AND s.created_at < :createdBefore
        """,
    )
    suspend fun deleteUnusedOlderThan(createdBefore: Instant): Int
}

interface StaleVoteBlockBulkRepository {
    suspend fun insertIgnoreAll(blockHashes: Collection<AttoHash>): Long
}
