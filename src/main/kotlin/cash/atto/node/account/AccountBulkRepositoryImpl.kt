package cash.atto.node.account

import cash.atto.commons.toBigInteger
import kotlinx.coroutines.reactive.awaitSingle
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import java.math.BigInteger
import java.util.concurrent.ConcurrentHashMap

@Component
class AccountBulkRepositoryImpl(
    private val databaseClient: DatabaseClient,
) : AccountBulkRepository {
    private val sqlByRowCount = ConcurrentHashMap<Int, String>()
    private val valuePlaceholders = "(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
    private val insertSql =
        """
        INSERT INTO account
          (
            public_key,
            network,
            version,
            algorithm,
            height,
            balance,
            last_transaction_timestamp,
            last_transaction_hash,
            representative_algorithm,
            representative_public_key,
            persisted_at,
            updated_at
          )
        """.trimIndent()
    private val duplicateUpdateSql =
        """
        ON DUPLICATE KEY UPDATE
          network = VALUES(network),
          version = VALUES(version),
          algorithm = VALUES(algorithm),
          height = VALUES(height),
          balance = VALUES(balance),
          last_transaction_timestamp = VALUES(last_transaction_timestamp),
          last_transaction_hash = VALUES(last_transaction_hash),
          representative_algorithm = VALUES(representative_algorithm),
          representative_public_key = VALUES(representative_public_key),
          updated_at = VALUES(updated_at)
        """.trimIndent()

    override suspend fun upsertAll(accounts: Collection<Account>): Long {
        if (accounts.isEmpty()) return 0

        return databaseClient
            .inConnection { connection ->
                val statement = connection.createStatement(sql(accounts.size))
                var bindIndex = 0

                accounts.forEach { account ->
                    statement
                        .bind(bindIndex++, account.publicKey.value)
                        .bind(bindIndex++, account.network.name)
                        .bind(bindIndex++, account.version.value.toShort())
                        .bind(bindIndex++, account.algorithm.name)
                        .bind(bindIndex++, BigInteger.valueOf(account.height))
                        .bind(bindIndex++, account.balance.raw.toBigInteger())
                        .bind(bindIndex++, account.lastTransactionTimestamp)
                        .bind(bindIndex++, account.lastTransactionHash.value)
                        .bind(bindIndex++, account.representativeAlgorithm.name)
                        .bind(bindIndex++, account.representativePublicKey.value)
                        .bind(bindIndex++, account.persistedAt!!)
                        .bind(bindIndex++, account.updatedAt!!)
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
