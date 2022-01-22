package org.atto.node.transaction

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import io.r2dbc.spi.Row
import io.r2dbc.spi.RowMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.reactive.awaitFirst
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.atto.commons.*
import org.atto.node.AttoRepository
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus
import org.springframework.context.annotation.DependsOn
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.function.BiFunction


@Repository
@DependsOn("flyway")
class TransactionRepository(
    properties: TransactionProperties,
    private val scope: CoroutineScope,
    private val client: DatabaseClient
) :
    AttoRepository<Transaction, AttoHash> {

    private val transactions: Cache<AttoHash, Deferred<Transaction?>> = Caffeine.newBuilder()
        .expireAfterAccess(properties.cacheExpirationTimeInSeconds!!, TimeUnit.SECONDS)
        .maximumSize(properties.cacheMaxSize!!.toLong())
        .build()

    private val hashLinkTransactions: Cache<AttoHash, Deferred<Transaction?>> = Caffeine.newBuilder()
        .expireAfterAccess(properties.cacheExpirationTimeInSeconds!!, TimeUnit.SECONDS)
        .maximumSize(properties.cacheMaxSize!!.toLong())
        .build()

    private val latestTransactions: Cache<AttoPublicKey, Deferred<Transaction?>> = Caffeine.newBuilder()
        .expireAfterAccess(properties.cacheExpirationTimeInSeconds!!, TimeUnit.SECONDS)
        .maximumSize(properties.cacheMaxSize!!.toLong())
        .build()

    // TODO change mapping to use position instead of name
    private val transactionMappingFunction: BiFunction<Row, RowMetadata, Transaction> =
        BiFunction<Row, RowMetadata, Transaction> { row: Row, _: RowMetadata? ->
            val type = AttoBlockType.valueOf(row["type", String::class.java]!!)

            val link = if (type == AttoBlockType.SEND) {
                AttoLink.from(AttoPublicKey(row["link", ByteArray::class.java]!!))
            } else {
                AttoLink.from(AttoHash(row["link", ByteArray::class.java]!!))
            }

            val block = AttoBlock(
                type = type,
                version = row["version", java.lang.Short::class.java]!!.toShort().toUShort(),
                publicKey = AttoPublicKey(row["publicKey", ByteArray::class.java]!!),
                height = row["height", java.lang.Long::class.java]!!.toLong().toULong(),
                previous = AttoHash(row["previous", ByteArray::class.java]!!),
                representative = AttoPublicKey(row["representative", ByteArray::class.java]!!),
                link = link,
                balance = AttoAmount(row["balance", java.lang.Long::class.java]!!.toLong().toULong()),
                amount = AttoAmount(row["amount", java.lang.Long::class.java]!!.toLong().toULong()),
                timestamp = Instant.ofEpochMilli(row["timestamp", java.lang.Long::class.java]!!.toLong())
            )

            Transaction(
                block = block,
                signature = AttoSignature(row["signature", ByteArray::class.java]!!),
                work = AttoWork(row["work", ByteArray::class.java]!!),
                hash = AttoHash(row["hash", ByteArray::class.java]!!),
                status = TransactionStatus.valueOf(row["status", String::class.java]!!),
                receivedTimestamp = Instant.ofEpochMilli(row["receivedTimestamp", java.lang.Long::class.java]!!.toLong())
            )
        }

    private val weightMappingFunction: BiFunction<Row, RowMetadata, Pair<AttoPublicKey, ULong>> =
        BiFunction<Row, RowMetadata, Pair<AttoPublicKey, ULong>> { row: Row, _: RowMetadata? ->
            val publicKey = AttoPublicKey(row["representative", ByteArray::class.java]!!)
            val weight = row["weight", java.lang.Long::class.java]!!.toLong().toULong()
            publicKey to weight
        }

    private val insertStatement = """
            INSERT INTO transactions
                        (
                                    hash,
                                    status,
                                    type,
                                    version,
                                    publicKey,
                                    height,
                                    previous,
                                    representative,
                                    link,
                                    balance,
                                    amount,
                                    timestamp,
                                    signature,
                                    work,
                                    receivedTimestamp
                        )
                        VALUES
                        (
                                    :hash,
                                    :status,
                                    :type,
                                    :version,
                                    :publicKey,
                                    :height,
                                    :previous,
                                    :representative,
                                    :link,
                                    :balance,
                                    :amount,
                                    :timestamp,
                                    :signature,
                                    :work,
                                    :receivedTimestamp
                        )
                        ON DUPLICATE KEY UPDATE status = VALUES(status);
    """.trimIndent()

    override suspend fun save(entity: Transaction): Transaction {
        val transaction = entity
        val block = transaction.block
        client.sql(insertStatement)
            .bind("hash", transaction.hash.value)
            .bind("status", transaction.status.toString())
            .bind("type", block.type.toString())
            .bind("version", block.version.toString())
            .bind("publicKey", block.publicKey.value)
            .bind("height", block.height.toString())
            .bind("previous", block.previous.value)
            .bind("representative", block.representative.value)
            .bind("link", block.link.toByteArray())
            .bind("balance", block.balance.raw.toLong())
            .bind("amount", block.amount.raw.toLong())
            .bind("timestamp", block.timestamp.toEpochMilli())
            .bind("signature", transaction.signature.value)
            .bind("work", transaction.work.value)
            .bind("receivedTimestamp", transaction.receivedTimestamp.toEpochMilli())
            .fetch()
            .awaitRowsUpdated()

        transactions.put(transaction.hash, scope.async { transaction })

        if (transaction.status == TransactionStatus.CONFIRMED) {
            latestTransactions.put(transaction.block.publicKey, scope.async { transaction })

            if (transaction.block.type == AttoBlockType.OPEN || transaction.block.type == AttoBlockType.RECEIVE) {
                hashLinkTransactions.put(transaction.block.link.hash, scope.async { transaction })
            }
        }

        return transaction
    }

    override suspend fun findById(id: AttoHash): Transaction? {
        return transactions.get(id) {
            scope.async {
                client.sql("SELECT * FROM transactions WHERE hash = ?")
                    .bind(0, id.value)
                    .map(transactionMappingFunction)
                    .one()
                    .awaitFirstOrNull()
            }
        }?.await()
    }

    suspend fun findConfirmedByHashLink(link: AttoHash): Transaction? {
        return hashLinkTransactions.get(link) {
            scope.async {
                client.sql("SELECT * FROM transactions WHERE status = ? and link = ?")
                    .bind(0, TransactionStatus.CONFIRMED)
                    .bind(1, link.value)
                    .map(transactionMappingFunction)
                    .one()
                    .awaitFirstOrNull()
            }
        }?.await()
    }

    suspend fun findLastConfirmedByPublicKeyId(publicKey: AttoPublicKey): Transaction? {
        val sql = """SELECT * FROM transactions t1
            |WHERE publicKey = ?
            |AND height = (SELECT MAX(height) FROM transactions t2 where t1.publicKey = t2.publicKey and t2.status = ?)
            |""".trimMargin()
        return latestTransactions.get(publicKey) {
            scope.async {
                client.sql(sql)
                    .bind(0, publicKey.value)
                    .bind(1, TransactionStatus.CONFIRMED)
                    .map(transactionMappingFunction)
                    .one()
                    .awaitFirstOrNull()
            }
        }?.await()
    }

    suspend fun findAnyTransaction(): Transaction? {
        val sql = "SELECT * FROM transactions t1"
        return client.sql(sql)
            .map(transactionMappingFunction)
            .first()
            .awaitFirstOrNull()
    }

    suspend fun findAllWeights(): Map<AttoPublicKey, ULong> {
        val sql = """
            |SELECT representative, CAST(SUM(balance) AS  BIGINT UNSIGNED) weight
            |FROM transactions t1 
            |WHERE status = 'CONFIRMED' 
            |AND height = (select MAX(height) FROM transactions t2 where t1.publicKey = t2.publicKey)
            |GROUP BY representative
            |""".trimMargin()
        return client.sql(sql)
            .map(weightMappingFunction)
            .all()
            .collectList()
            .awaitFirst()
            .toMap()
    }

    override suspend fun deleteAll(): Int {
        transactions.invalidateAll()
        latestTransactions.invalidateAll()

        return client.sql("DELETE FROM transactions")
            .fetch()
            .awaitRowsUpdated()
    }

}