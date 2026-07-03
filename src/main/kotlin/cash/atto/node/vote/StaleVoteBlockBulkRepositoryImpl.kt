package cash.atto.node.vote

import cash.atto.commons.AttoHash
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap

@Component
class StaleVoteBlockBulkRepositoryImpl(
    private val databaseClient: DatabaseClient,
) : StaleVoteBlockBulkRepository {
    private val sqlByRowCount = ConcurrentHashMap<Int, String>()
    private val valuePlaceholders = "(?)"
    private val insertSql = "INSERT INTO stale_vote_block (block_hash)"
    private val duplicateUpdateSql = "ON DUPLICATE KEY UPDATE block_hash = block_hash"

    override suspend fun insertIgnoreAll(blockHashes: Collection<AttoHash>): Long {
        val distinctBlockHashes = blockHashes.distinct()
        if (distinctBlockHashes.isEmpty()) return 0

        return databaseClient
            .inConnection { connection ->
                val statement = connection.createStatement(sql(distinctBlockHashes.size))
                var bindIndex = 0

                distinctBlockHashes.forEach { blockHash ->
                    statement.bind(bindIndex++, blockHash.value)
                }

                Flux
                    .from(statement.execute())
                    .flatMap { it.rowsUpdated }
                    .reduce(0L, Long::plus)
            }.awaitSingle()
    }

    private fun sql(rowCount: Int): String =
        sqlByRowCount.computeIfAbsent(rowCount) {
            "$insertSql VALUES ${List(rowCount) { valuePlaceholders }.joinToString(", ")} $duplicateUpdateSql"
        }
}
