package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.convertion.ConversionsConfiguration
import cash.atto.node.convertion.UncheckedTransactionDeserializerDBConverter
import cash.atto.node.transaction.Transaction
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.r2dbc.convert.MappingR2dbcConverter
import org.springframework.data.r2dbc.core.DefaultReactiveDataAccessStrategy
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.r2dbc.dialect.MySqlDialect
import org.springframework.data.r2dbc.repository.support.R2dbcRepositoryFactory
import org.springframework.data.relational.core.mapping.RelationalMappingContext
import org.springframework.r2dbc.core.DatabaseClient
import org.testcontainers.containers.MySQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.ByteBuffer
import java.sql.Connection
import java.sql.Timestamp
import java.time.Instant

@Testcontainers
class UncheckedTransactionMigrationTest {
    @BeforeEach
    fun cleanDatabase() {
        flyway().clean()
    }

    @Test
    fun `migrates a clean database through the oldest index`() {
        // when
        flyway().migrate()

        // then
        assertEquals(
            "11",
            flyway()
                .info()
                .current()
                .version
                .version,
        )
        mysql.createConnection("").use { connection ->
            assertEquals(
                listOf("timestamp", "public_key", "height"),
                indexColumns(connection),
            )
        }
    }

    @Test
    fun `migrates a populated V10 database without changing unchecked rows`() {
        // given
        flyway(MigrationVersion.fromVersion("10")).migrate()
        mysql.createConnection("").use { connection ->
            seedUncheckedTransactions(connection, 5_000)
            val before = uncheckedChecksum(connection)

            // when
            flyway().migrate()

            // then
            assertEquals(before, uncheckedChecksum(connection))
            assertEquals(
                listOf("timestamp", "public_key", "height"),
                indexColumns(connection),
            )

            val plan = resolutionPlan(connection)
            assertTrue(plan.contains("Covering index scan on candidate using unchecked_transaction_oldest"), plan)
            assertEquals(1_000, resolutionCount(connection))

            assertTrue(hasHigherUncheckedTransaction(connection, bytes(7), 4_999))
            assertTrue(
                higherUncheckedTransactionPlan(connection, bytes(7), 4_999)
                    .contains("unchecked_transaction_public_key_height"),
            )
            assertTrue(!hasHigherUncheckedTransaction(connection, bytes(7), 5_000))

            assertEquals(1_000, cleanUp(connection, 1_000))
            assertEquals(4_000L, uncheckedCount(connection))
        }
    }

    @Test
    fun `persists a full multi-row batch while ignoring duplicates through R2DBC`() =
        runBlocking {
            // given
            flyway().migrate()
            val databaseClient = databaseClient()
            val inserter = UncheckedTransactionInserter(databaseClient)
            val transactions = (0..<1_000).map { uncheckedTransaction(it) }

            // when
            val affected = inserter.insert(transactions)
            inserter.insert(transactions)
            val newTransaction = uncheckedTransaction(2_000)
            inserter.insert(listOf(newTransaction, newTransaction))
            val existingHeight = uncheckedTransaction(2_001)
            val conflictingHeight =
                existingHeight.copy(
                    block =
                        (existingHeight.block as AttoReceiveBlock).copy(
                            sendHash = AttoHash(hash(99_999)),
                        ),
                )
            inserter.insert(listOf(existingHeight, conflictingHeight))

            // then
            assertEquals(1_000L, affected)
            assertEquals(1L, hasHigherUncheckedTransaction(databaseClient, hash(1), 1))
            assertEquals(0L, hasHigherUncheckedTransaction(databaseClient, hash(1), 2))
            mysql.createConnection("").use { connection ->
                connection
                    .createStatement()
                    .use { statement ->
                        statement
                            .executeQuery(
                                "SELECT COUNT(*), SUM(previous IS NULL) FROM unchecked_transaction",
                            ).use { results ->
                                results.next()
                                assertEquals(1_002L, results.getLong(1))
                                assertEquals(1L, results.getLong(2))
                            }
                    }
            }
        }

    @Test
    fun `maps serialized resolution candidates in deterministic order`() =
        runBlocking {
            // given
            flyway().migrate()
            val databaseClient = databaseClient()
            val inserter = UncheckedTransactionInserter(databaseClient)
            val timestamp = AttoInstant.now()
            val transactions = (0..<3).map { uncheckedTransaction(it, timestamp) }
            inserter.insert(transactions.reversed())

            // when
            val candidates =
                databaseClient
                    .sql(
                        """
                        SELECT ut.serialized
                        FROM (
                          SELECT candidate.hash, candidate.timestamp, candidate.public_key, candidate.height
                          FROM unchecked_transaction candidate
                          LEFT JOIN account a ON a.public_key = candidate.public_key
                          WHERE candidate.height > COALESCE(a.height, 0)
                          ORDER BY candidate.timestamp, candidate.public_key, candidate.height
                          LIMIT :limit
                        ) oldest
                        JOIN unchecked_transaction ut ON ut.hash = oldest.hash
                        ORDER BY oldest.timestamp, oldest.public_key, oldest.height
                        """.trimIndent(),
                    ).bind("limit", transactions.size.toLong())
                    .map { row, _ -> UncheckedTransactionDeserializerDBConverter().convert(row) }
                    .all()
                    .collectList()
                    .awaitSingle()

            // then
            assertEquals(transactions.map { it.hash }, candidates.map { it.hash })
        }

    @Test
    fun `prioritizes account gaps by oldest boundary timestamp`() =
        runBlocking {
            // given
            flyway().migrate()
            val firstPublicKey = bytes(1)
            val secondPublicKey = bytes(2)
            mysql.createConnection("").use { connection ->
                seedAccount(connection, firstPublicKey, 2)
                seedAccount(connection, secondPublicKey, 0)
                seedUncheckedTransaction(connection, firstPublicKey, 3, 1_003)
                seedUncheckedTransaction(connection, firstPublicKey, 6, 1_005)
                seedUncheckedTransaction(connection, secondPublicKey, 3, 2_002)
            }
            val repository = uncheckedTransactionRepository()

            // when
            val gaps = repository.findGaps(2).toList()

            // then
            assertEquals(
                listOf(
                    GapView(
                        publicKey = AttoPublicKey(secondPublicKey),
                        startHeight = 1U.toAttoHeight(),
                        endHeight = 2U.toAttoHeight(),
                        expectedEndHash = AttoHash(hash(2_002)),
                    ),
                    GapView(
                        publicKey = AttoPublicKey(firstPublicKey),
                        startHeight = 4U.toAttoHeight(),
                        endHeight = 5U.toAttoHeight(),
                        expectedEndHash = AttoHash(hash(1_005)),
                    ),
                ),
                gaps,
            )
        }

    @Test
    fun `selects the lowest gap for an account before applying timestamp priority`() =
        runBlocking {
            // given
            flyway().migrate()
            val publicKey = bytes(5)
            mysql.createConnection("").use { connection ->
                seedAccount(connection, publicKey, 0)
                seedUncheckedTransaction(
                    connection,
                    publicKey,
                    height = 2,
                    previous = 5_001,
                    timestamp = Instant.parse("2026-08-03T00:00:10Z"),
                )
                seedUncheckedTransaction(
                    connection,
                    publicKey,
                    height = 5,
                    previous = 5_004,
                    timestamp = Instant.parse("2026-08-03T00:00:01Z"),
                )
            }

            // when
            val gap = uncheckedTransactionRepository().findGaps(1).toList().single()

            // then
            assertEquals(
                GapView(
                    publicKey = AttoPublicKey(publicKey),
                    startHeight = 1U.toAttoHeight(),
                    endHeight = 1U.toAttoHeight(),
                    expectedEndHash = AttoHash(hash(5_001)),
                ),
                gap,
            )
        }

    @Test
    fun `finds an initial gap for an account missing from the account table`() =
        runBlocking {
            // given
            flyway().migrate()
            val publicKey = bytes(3)
            mysql.createConnection("").use { connection ->
                seedUncheckedTransaction(connection, publicKey, 2, 3_001)
            }

            // when
            val gap = uncheckedTransactionRepository().findGaps(1).toList().single()

            // then
            assertEquals(
                GapView(
                    publicKey = AttoPublicKey(publicKey),
                    startHeight = 1U.toAttoHeight(),
                    endHeight = 1U.toAttoHeight(),
                    expectedEndHash = AttoHash(hash(3_001)),
                ),
                gap,
            )
        }

    @Test
    fun `returns no gap for contiguous unchecked transactions`() =
        runBlocking {
            // given
            flyway().migrate()
            val publicKey = bytes(4)
            mysql.createConnection("").use { connection ->
                seedAccount(connection, publicKey, 2)
                seedUncheckedTransaction(connection, publicKey, 3, 4_002)
                seedUncheckedTransaction(connection, publicKey, 4, 4_003)
            }

            // when
            val gaps = uncheckedTransactionRepository().findGaps(1).toList()

            // then
            assertTrue(gaps.isEmpty())
        }

    private suspend fun hasHigherUncheckedTransaction(
        databaseClient: DatabaseClient,
        publicKey: ByteArray,
        height: Long,
    ): Long =
        databaseClient
            .sql(
                """
                SELECT EXISTS (
                  SELECT 1
                  FROM unchecked_transaction
                  WHERE public_key = :publicKey
                    AND height > :height
                )
                """.trimIndent(),
            ).bind("publicKey", publicKey)
            .bind("height", height)
            .map { row -> row.get(0, Long::class.javaObjectType)!! }
            .one()
            .awaitSingle()

    private fun flyway(target: MigrationVersion? = null): Flyway {
        val configuration =
            Flyway
                .configure()
                .dataSource(mysql.jdbcUrl, mysql.username, mysql.password)
                .cleanDisabled(false)
        if (target != null) {
            configuration.target(target)
        }
        return configuration.load()
    }

    private fun seedUncheckedTransactions(
        connection: Connection,
        count: Int,
    ) {
        val publicKey = bytes(7)
        val baseTimestamp = Instant.parse("2026-08-03T00:00:00Z")

        connection
            .prepareStatement(
                """
                INSERT INTO account (
                  public_key, network, version, algorithm, height, balance,
                  last_transaction_timestamp, last_transaction_hash,
                  representative_algorithm, representative_public_key
                ) VALUES (?, 'LOCAL', 0, 'V1', 4000, 0, ?, ?, 'V1', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, publicKey)
                statement.setTimestamp(2, Timestamp.from(baseTimestamp))
                statement.setBytes(3, bytes(8))
                statement.setBytes(4, publicKey)
                statement.executeUpdate()
            }

        connection.autoCommit = false
        connection
            .prepareStatement(
                """
                INSERT INTO unchecked_transaction (
                  hash, public_key, height, previous, serialized, timestamp, received_at
                ) VALUES (?, ?, ?, NULL, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                repeat(count) { offset ->
                    val height = offset + 1L
                    val timestamp = Timestamp.from(baseTimestamp.plusMillis(height))
                    statement.setBytes(1, hash(height))
                    statement.setBytes(2, publicKey)
                    statement.setLong(3, height)
                    statement.setBytes(4, byteArrayOf(1))
                    statement.setTimestamp(5, timestamp)
                    statement.setTimestamp(6, timestamp)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        connection.commit()
        connection.autoCommit = true
    }

    private fun seedAccount(
        connection: Connection,
        publicKey: ByteArray,
        height: Long,
    ) {
        val timestamp = Timestamp.from(Instant.parse("2026-08-03T00:00:00Z"))
        connection
            .prepareStatement(
                """
                INSERT INTO account (
                  public_key, network, version, algorithm, height, balance,
                  last_transaction_timestamp, last_transaction_hash,
                  representative_algorithm, representative_public_key
                ) VALUES (?, 'LOCAL', 0, 'V1', ?, 0, ?, ?, 'V1', ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, publicKey)
                statement.setLong(2, height)
                statement.setTimestamp(3, timestamp)
                statement.setBytes(4, hash(height))
                statement.setBytes(5, publicKey)
                statement.executeUpdate()
            }
    }

    private fun seedUncheckedTransaction(
        connection: Connection,
        publicKey: ByteArray,
        height: Long,
        previous: Long,
        timestamp: Instant = Instant.parse("2026-08-03T00:00:00Z").plusMillis(height),
    ) {
        val sqlTimestamp = Timestamp.from(timestamp)
        connection
            .prepareStatement(
                """
                INSERT INTO unchecked_transaction (
                  hash, public_key, height, previous, serialized, timestamp, received_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, hash(previous + 1))
                statement.setBytes(2, publicKey)
                statement.setLong(3, height)
                statement.setBytes(4, hash(previous))
                statement.setBytes(5, byteArrayOf(1))
                statement.setTimestamp(6, sqlTimestamp)
                statement.setTimestamp(7, sqlTimestamp)
                statement.executeUpdate()
            }
    }

    private fun resolutionPlan(connection: Connection): String =
        connection
            .createStatement()
            .use { statement ->
                statement
                    .executeQuery(
                        """
                        EXPLAIN FORMAT=TREE
                        SELECT ut.serialized
                        FROM (
                          SELECT candidate.hash, candidate.timestamp, candidate.public_key, candidate.height
                          FROM unchecked_transaction candidate
                          LEFT JOIN account a ON a.public_key = candidate.public_key
                          WHERE candidate.height > COALESCE(a.height, 0)
                          ORDER BY candidate.timestamp, candidate.public_key, candidate.height
                          LIMIT 1000
                        ) oldest
                        JOIN unchecked_transaction ut ON ut.hash = oldest.hash
                        ORDER BY oldest.timestamp, oldest.public_key, oldest.height
                        """.trimIndent(),
                    ).use { results ->
                        results.next()
                        results.getString(1)
                    }
            }

    private fun resolutionCount(connection: Connection): Int =
        connection
            .createStatement()
            .use { statement ->
                statement
                    .executeQuery(
                        """
                        SELECT COUNT(*)
                        FROM (
                          SELECT candidate.hash
                          FROM unchecked_transaction candidate
                          LEFT JOIN account a ON a.public_key = candidate.public_key
                          WHERE candidate.height > COALESCE(a.height, 0)
                          ORDER BY candidate.timestamp, candidate.public_key, candidate.height
                          LIMIT 1000
                        ) oldest
                        """.trimIndent(),
                    ).use { results ->
                        results.next()
                        results.getInt(1)
                    }
            }

    private fun hasHigherUncheckedTransaction(
        connection: Connection,
        publicKey: ByteArray,
        height: Long,
    ): Boolean =
        connection
            .prepareStatement(
                """
                SELECT EXISTS (
                  SELECT 1
                  FROM unchecked_transaction
                  WHERE public_key = ?
                    AND height > ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, publicKey)
                statement.setLong(2, height)
                statement.executeQuery().use { results ->
                    results.next()
                    results.getBoolean(1)
                }
            }

    private fun higherUncheckedTransactionPlan(
        connection: Connection,
        publicKey: ByteArray,
        height: Long,
    ): String =
        connection
            .prepareStatement(
                """
                EXPLAIN FORMAT=TREE
                SELECT EXISTS (
                  SELECT 1
                  FROM unchecked_transaction
                  WHERE public_key = ?
                    AND height > ?
                )
                """.trimIndent(),
            ).use { statement ->
                statement.setBytes(1, publicKey)
                statement.setLong(2, height)
                statement.executeQuery().use { results ->
                    results.next()
                    results.getString(1)
                }
            }

    private fun cleanUp(
        connection: Connection,
        limit: Int,
    ): Int =
        connection
            .prepareStatement(
                """
                DELETE FROM unchecked_transaction
                WHERE EXISTS (
                  SELECT 1
                  FROM account a
                  WHERE a.public_key = unchecked_transaction.public_key
                    AND unchecked_transaction.height <= a.height
                )
                LIMIT ?
                """.trimIndent(),
            ).use { statement ->
                statement.setInt(1, limit)
                statement.executeUpdate()
            }

    private fun indexColumns(connection: Connection): List<String> =
        connection
            .prepareStatement(
                """
                SELECT column_name
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'unchecked_transaction'
                  AND index_name = 'unchecked_transaction_oldest'
                ORDER BY seq_in_index
                """.trimIndent(),
            ).use { statement ->
                statement.executeQuery().use { results ->
                    buildList {
                        while (results.next()) {
                            add(results.getString(1))
                        }
                    }
                }
            }

    private fun uncheckedChecksum(connection: Connection): Pair<Long, String> =
        connection
            .createStatement()
            .use { statement ->
                statement
                    .executeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(CRC32(hash)), 0) FROM unchecked_transaction",
                    ).use { results ->
                        results.next()
                        results.getLong(1) to results.getBigDecimal(2).toPlainString()
                    }
            }

    private fun uncheckedCount(connection: Connection): Long =
        connection
            .createStatement()
            .use { statement ->
                statement.executeQuery("SELECT COUNT(*) FROM unchecked_transaction").use { results ->
                    results.next()
                    results.getLong(1)
                }
            }

    private fun connectionFactory(): ConnectionFactory {
        val options =
            ConnectionFactoryOptions
                .parse(mysql.jdbcUrl.replace("jdbc:", "r2dbc:"))
                .mutate()
                .option(ConnectionFactoryOptions.USER, mysql.username)
                .option(ConnectionFactoryOptions.PASSWORD, mysql.password)
                .build()
        return ConnectionFactories.get(options)
    }

    private fun databaseClient(): DatabaseClient = DatabaseClient.create(connectionFactory())

    private fun uncheckedTransactionRepository(): UncheckedTransactionRepository {
        val conversions = ConversionsConfiguration().customConversions()
        val mappingContext =
            RelationalMappingContext().apply {
                setSimpleTypeHolder(conversions.simpleTypeHolder)
                setInitialEntitySet(setOf(UncheckedTransaction::class.java))
                afterPropertiesSet()
            }
        val converter = MappingR2dbcConverter(mappingContext, conversions)
        val strategy = DefaultReactiveDataAccessStrategy(MySqlDialect.INSTANCE, converter)
        val template = R2dbcEntityTemplate(DatabaseClient.create(connectionFactory()), strategy)
        return R2dbcRepositoryFactory(template).getRepository(UncheckedTransactionRepository::class.java)
    }

    private fun hash(value: Long): ByteArray =
        ByteArray(32).also {
            ByteBuffer.wrap(it, 24, Long.SIZE_BYTES).putLong(value)
        }

    private fun uncheckedTransaction(
        index: Int,
        timestamp: AttoInstant = AttoInstant.now(),
    ): UncheckedTransaction {
        val publicKey = AttoPublicKey(hash(index.toLong()))
        val block =
            if (index == 0) {
                AttoOpenBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    balance = AttoAmount.MAX,
                    timestamp = timestamp,
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(hash(10_000)),
                    representativeAlgorithm = AttoAlgorithm.V1,
                    representativePublicKey = publicKey,
                )
            } else {
                AttoReceiveBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    height = 2U.toAttoHeight(),
                    balance = AttoAmount.MAX,
                    timestamp = timestamp,
                    previous = AttoHash(hash(index.toLong() + 10_000)),
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(hash(index.toLong() + 20_000)),
                )
            }

        return Transaction(
            block = block,
            signature = AttoSignature(ByteArray(64) { index.toByte() }),
            work = AttoWork(ByteArray(8) { (index + 1).toByte() }),
        ).toUncheckedTransaction()
    }

    private fun bytes(marker: Byte): ByteArray = ByteArray(32) { marker }

    companion object {
        @Container
        @JvmStatic
        val mysql: MySQLContainer<*> =
            MySQLContainer("mysql:8.4")
                .withDatabaseName("node")
                .withUsername("node")
                .withPassword("node")
    }
}
