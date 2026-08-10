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
import cash.atto.node.transaction.Transaction
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.Result
import io.r2dbc.spi.Statement
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.DatabaseClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.concurrent.TimeUnit

class UncheckedTransactionInserterTest {
    @Test
    fun `inserts a collection with one multi-row statement`() =
        runTest {
            // given
            val connectionFactory = mockk<ConnectionFactory>()
            val connectionFactoryMetadata = mockk<ConnectionFactoryMetadata>()
            val connection = mockk<Connection>()
            val statement = mockk<Statement>()
            val result = mockk<Result>()
            val sql = slot<String>()

            every { connectionFactory.metadata } returns connectionFactoryMetadata
            every { connectionFactoryMetadata.name } returns "MySQL"
            every { connectionFactory.create() } returns Mono.just(connection)
            every { connection.createStatement(capture(sql)) } returns statement
            every { connection.close() } returns Mono.empty()
            every { statement.bind(any<Int>(), any()) } returns statement
            every { statement.bindNull(any<Int>(), any()) } returns statement
            every { statement.execute() } returns Flux.just(result)
            every { result.rowsUpdated } returns Mono.just(2L)

            val meterRegistry = SimpleMeterRegistry()
            val inserter = UncheckedTransactionInserter(DatabaseClient.create(connectionFactory), meterRegistry)
            val transactions = listOf(openTransaction(), receiveTransaction())

            // when
            val inserted = inserter.insert(transactions)

            // then
            assertEquals(2L, inserted)
            assertTrue(
                sql.captured.contains(
                    "VALUES (?, ?, ?, ?, ?, ?, ?), (?, ?, ?, ?, ?, ?, ?)",
                ),
            )
            assertTrue(sql.captured.endsWith("ON DUPLICATE KEY UPDATE hash = hash"))
            val timer = meterRegistry.get(UncheckedTransactionInserter.METRIC_NAME).timer()
            assertEquals(1L, timer.count())
            assertTrue(timer.totalTime(TimeUnit.NANOSECONDS) > 0)
            verify(exactly = 1) { connection.createStatement(any()) }
            verify(exactly = 13) { statement.bind(any<Int>(), any()) }
            verify(exactly = 1) { statement.bindNull(3, ByteArray::class.java) }
            verify(exactly = 0) { statement.add() }
            verify(exactly = 1) { statement.execute() }
        }

    @Test
    fun `does not acquire a connection for an empty collection`() =
        runTest {
            // given
            val connectionFactory = mockk<ConnectionFactory>()
            val connectionFactoryMetadata = mockk<ConnectionFactoryMetadata>()
            every { connectionFactory.metadata } returns connectionFactoryMetadata
            every { connectionFactoryMetadata.name } returns "MySQL"
            val meterRegistry = SimpleMeterRegistry()
            val inserter = UncheckedTransactionInserter(DatabaseClient.create(connectionFactory), meterRegistry)

            // when
            val inserted = inserter.insert(emptyList())

            // then
            assertEquals(0L, inserted)
            assertEquals(0L, meterRegistry.get(UncheckedTransactionInserter.METRIC_NAME).timer().count())
            verify(exactly = 0) { connectionFactory.create() }
        }

    private fun openTransaction(): UncheckedTransaction {
        val publicKey = AttoPublicKey(ByteArray(32) { 1 })
        return Transaction(
            block =
                AttoOpenBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(ByteArray(32) { 2 }),
                    representativeAlgorithm = AttoAlgorithm.V1,
                    representativePublicKey = publicKey,
                ),
            signature = AttoSignature(ByteArray(64) { 3 }),
            work = AttoWork(ByteArray(8) { 4 }),
        ).toUncheckedTransaction()
    }

    private fun receiveTransaction(): UncheckedTransaction =
        Transaction(
            block =
                AttoReceiveBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(ByteArray(32) { 5 }),
                    height = 2U.toAttoHeight(),
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = AttoHash(ByteArray(32) { 6 }),
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(ByteArray(32) { 7 }),
                ),
            signature = AttoSignature(ByteArray(64) { 8 }),
            work = AttoWork(ByteArray(8) { 9 }),
        ).toUncheckedTransaction()
}
