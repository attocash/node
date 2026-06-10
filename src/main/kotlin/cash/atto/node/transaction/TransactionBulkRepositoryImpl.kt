package cash.atto.node.transaction

import cash.atto.commons.toBigInteger
import cash.atto.commons.toJavaInstant
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.io.readByteArray
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap

@Component
class TransactionBulkRepositoryImpl(
    private val databaseClient: DatabaseClient,
) : TransactionBulkRepository {
    private val sqlByRowCount = ConcurrentHashMap<Int, String>()
    private val valuePlaceholders = "(?, ?, ?, ?, ?, ?, ?, ?)"
    private val insertSql =
        """
        INSERT INTO transaction
          (hash, algorithm, public_key, height, block_type, timestamp, serialized, received_at)
        """.trimIndent()

    override suspend fun insertAll(transactions: Collection<Transaction>): Long {
        if (transactions.isEmpty()) return 0

        return databaseClient
            .inConnection { connection ->
                val statement = connection.createStatement(sql(transactions.size))
                var bindIndex = 0

                transactions.forEach { transaction ->
                    val block = transaction.block

                    statement
                        .bind(bindIndex++, block.hash.value)
                        .bind(bindIndex++, block.algorithm.name)
                        .bind(bindIndex++, block.publicKey.value)
                        .bind(bindIndex++, block.height.value.toBigInteger())
                        .bind(bindIndex++, block.type.name)
                        .bind(bindIndex++, block.timestamp.toJavaInstant())
                        .bind(bindIndex++, transaction.toAttoTransaction().toBuffer().readByteArray())
                        .bind(bindIndex++, transaction.receivedAt)
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
