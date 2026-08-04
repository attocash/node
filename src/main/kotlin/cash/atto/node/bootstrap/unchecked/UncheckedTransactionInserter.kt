package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.toBigInteger
import cash.atto.commons.toJavaInstant
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.io.readByteArray
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap

@Component
class UncheckedTransactionInserter(
    private val databaseClient: DatabaseClient,
) {
    private val sqlByRowCount = ConcurrentHashMap<Int, String>()
    private val valuePlaceholders = "(?, ?, ?, ?, ?, ?, ?)"
    private val insertSql =
        """
        INSERT INTO unchecked_transaction
          (hash, public_key, height, previous, `timestamp`, serialized, received_at)
        """.trimIndent()
    private val duplicateUpdateSql = "ON DUPLICATE KEY UPDATE hash = hash"

    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun insert(uncheckedTransactions: Collection<UncheckedTransaction>): Long {
        if (uncheckedTransactions.isEmpty()) return 0

        return databaseClient
            .inConnection { conn ->
                val statement = conn.createStatement(sql(uncheckedTransactions.size))
                var bindIndex = 0

                uncheckedTransactions.forEach { transaction ->
                    val previous = transaction.previous

                    statement
                        .bind(bindIndex++, transaction.hash.value)
                        .bind(bindIndex++, transaction.publicKey.value)
                        .bind(bindIndex++, transaction.height.value.toBigInteger())

                    if (previous != null) {
                        statement.bind(bindIndex++, previous.value)
                    } else {
                        statement.bindNull(bindIndex++, ByteArray::class.java)
                    }

                    statement
                        .bind(bindIndex++, transaction.block.timestamp.toJavaInstant())
                        .bind(
                            bindIndex++,
                            transaction
                                .toTransaction()
                                .toAttoTransaction()
                                .toBuffer()
                                .readByteArray(),
                        ).bind(bindIndex++, transaction.receivedAt)
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
