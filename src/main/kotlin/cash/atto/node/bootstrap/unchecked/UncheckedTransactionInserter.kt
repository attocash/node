package cash.atto.node.bootstrap.unchecked

import cash.atto.node.toBigInteger
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.datetime.toJavaInstant
import kotlinx.io.readByteArray
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Flux

@Component
class UncheckedTransactionInserter(
    private val databaseClient: DatabaseClient,
) {
    private val sql =
        """
        INSERT INTO unchecked_transaction
          (hash, public_key, height, previous, `timestamp`, serialized, received_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON DUPLICATE KEY UPDATE hash = hash
        """.trimIndent()

    @Transactional(isolation = Isolation.READ_COMMITTED)
    suspend fun insert(uncheckedTransactions: Collection<UncheckedTransaction>): Long {
        if (uncheckedTransactions.isEmpty()) return 0

        return databaseClient
            .inConnection { conn ->
                val last = uncheckedTransactions.size - 1

                val statement = conn.createStatement(sql)
                uncheckedTransactions.forEachIndexed { i, t ->
                    statement.apply {
                        bind(0, t.hash.value)
                        bind(1, t.publicKey.value)
                        bind(2, t.height.value.toBigInteger())
                        if (t.previous != null) {
                            bind(3, t.previous.value)
                        } else {
                            bindNull(3, ByteArray::class.java)
                        }
                        bind(4, t.block.timestamp.toJavaInstant())
                        bind(
                            5,
                            t
                                .toTransaction()
                                .toAttoTransaction()
                                .toBuffer()
                                .readByteArray(),
                        )
                        bind(6, t.receivedAt)

                        if (i != last) {
                            add()
                        }
                    }
                }
                Flux
                    .from(statement.execute())
                    .flatMap { it.rowsUpdated }
                    .reduce(0L, Long::plus)
            }.awaitSingle()
    }
}
