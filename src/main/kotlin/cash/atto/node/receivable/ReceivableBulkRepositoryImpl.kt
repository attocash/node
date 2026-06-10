package cash.atto.node.receivable

import cash.atto.commons.toBigInteger
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap

@Component
class ReceivableBulkRepositoryImpl(
    private val databaseClient: DatabaseClient,
) : ReceivableBulkRepository {
    private val sqlByRowCount = ConcurrentHashMap<Int, String>()
    private val valuePlaceholders = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    private val insertSql =
        """
        INSERT INTO receivable
          (
            hash,
            network,
            version,
            algorithm,
            public_key,
            timestamp,
            receiver_algorithm,
            receiver_public_key,
            amount,
            persisted_at
          )
        """.trimIndent()

    override suspend fun insertAll(receivables: Collection<Receivable>): Long {
        if (receivables.isEmpty()) return 0

        return databaseClient
            .inConnection { connection ->
                val statement = connection.createStatement(sql(receivables.size))
                var bindIndex = 0

                receivables.forEach { receivable ->
                    statement
                        .bind(bindIndex++, receivable.hash.value)
                        .bind(bindIndex++, receivable.network.name)
                        .bind(bindIndex++, receivable.version.value.toShort())
                        .bind(bindIndex++, receivable.algorithm.name)
                        .bind(bindIndex++, receivable.publicKey.value)
                        .bind(bindIndex++, receivable.timestamp)
                        .bind(bindIndex++, receivable.receiverAlgorithm.name)
                        .bind(bindIndex++, receivable.receiverPublicKey.value)
                        .bind(bindIndex++, receivable.amount.raw.toBigInteger())
                        .bind(bindIndex++, receivable.persistedAt!!)
                }

                Flux
                    .from(statement.execute())
                    .flatMap { it.rowsUpdated }
                    .reduce(0L, Long::plus)
            }.awaitSingle()
    }

    private fun sql(rowCount: Int): String =
        sqlByRowCount.computeIfAbsent(rowCount) {
            "$insertSql VALUES ${List(rowCount) { valuePlaceholders }.joinToString(", ")}"
        }
}
