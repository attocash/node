package cash.atto.node.vote

import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap

@Component
class VoteBulkRepositoryImpl(
    private val databaseClient: DatabaseClient,
) : VoteBulkRepository {
    private val sqlByRowCount = ConcurrentHashMap<Int, String>()
    private val valuePlaceholders = "(?, ?, ?, ?, ?, ?, ?, ?, ?)"
    private val insertSql =
        """
        INSERT INTO vote
          (hash, version, algorithm, public_key, block_algorithm, block_hash, timestamp, signature, received_at)
        """.trimIndent()
    private val duplicateUpdateSql = "ON DUPLICATE KEY UPDATE signature = signature"

    override suspend fun insertIgnoreAll(votes: Collection<Vote>): Long {
        if (votes.isEmpty()) return 0

        return databaseClient
            .inConnection { connection ->
                val statement = connection.createStatement(sql(votes.size))
                var bindIndex = 0

                votes.forEach { vote ->
                    statement
                        .bind(bindIndex++, vote.hash.value)
                        .bind(bindIndex++, vote.version.value.toShort())
                        .bind(bindIndex++, vote.algorithm.name)
                        .bind(bindIndex++, vote.publicKey.value)
                        .bind(bindIndex++, vote.blockAlgorithm.name)
                        .bind(bindIndex++, vote.blockHash.value)
                        .bind(bindIndex++, vote.timestamp.toEpochMilli())
                        .bind(bindIndex++, vote.signature.value)
                        .bind(bindIndex++, vote.receivedAt)
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
