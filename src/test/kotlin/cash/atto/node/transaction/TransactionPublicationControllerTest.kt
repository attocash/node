package cash.atto.node.transaction

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.commons.toPublicKeyBlocking
import cash.atto.commons.worker.AttoWorker
import cash.atto.node.ApplicationProperties
import cash.atto.node.account.Account
import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.ElectionRejected
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress
import java.net.URI
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

internal class TransactionPublicationControllerTest {
    private val privateKey = AttoPrivateKey.generate()
    private val block =
        AttoReceiveBlock(
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKeyBlocking(),
            height = AttoHeight(2UL),
            balance = AttoAmount(1UL),
            timestamp = AttoInstant.now(),
            previous = AttoHash(Random.nextBytes(ByteArray(32))),
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
        )
    private val transaction =
        Transaction(
            block = block,
            signature = runBlocking { privateKey.sign(block.hash) },
            work = runBlocking { AttoWorker.cpu().work(block) },
        )
    private val account =
        Account(
            publicKey = block.publicKey,
            network = block.network,
            version = block.version,
            algorithm = block.algorithm,
            height = block.height.value.toLong(),
            balance = block.balance,
            lastTransactionTimestamp = block.timestamp.toJavaInstant(),
            lastTransactionHash = block.hash,
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        )
    private val node =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0U,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            publicUri = URI("ws://localhost:8081"),
            features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL),
        )

    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `election rejection resolves publication and stream waiters according to stored hash`(confirmed: Boolean) =
        runTest {
            // Given
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            coEvery { repository.findById(transaction.hash) } returns transaction.takeIf { confirmed }
            val controller = TransactionPublicationController(ApplicationProperties(), node, messagePublisher, repository)
            val request =
                MockServerHttpRequest
                    .post("/transactions")
                    .remoteAddress(InetSocketAddress("127.0.0.1", 12345))
                    .build()
            val published =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { controller.publish(transaction.toAttoTransaction(), request) }
                }
            val streamed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { controller.publishAndStream(transaction.toAttoTransaction(), request).first() }
                }

            // When
            controller.process(ElectionRejected(account, transaction))
            val results = withTimeout(1.seconds) { listOf(published.await(), streamed.await()) }

            // Then
            results.forEach { result ->
                if (confirmed) {
                    assertEquals(transaction.toAttoTransaction(), result.getOrThrow())
                } else {
                    val failure = assertInstanceOf(ResponseStatusException::class.java, result.exceptionOrNull())
                    assertEquals(HttpStatus.CONFLICT, failure.statusCode)
                    assertEquals("Transaction rejected by local election", failure.reason)
                }
            }
            coVerify(exactly = 1) { repository.findById(transaction.hash) }
        }

    @Test
    fun `election rejection lookup failure completes all waiters without exposing database details`() =
        runTest {
            // Given
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            coEvery { repository.findById(transaction.hash) } throws IllegalStateException("Private database details")
            val controller = TransactionPublicationController(ApplicationProperties(), node, messagePublisher, repository)
            val request =
                MockServerHttpRequest
                    .post("/transactions")
                    .remoteAddress(InetSocketAddress("127.0.0.1", 12345))
                    .build()
            val published =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { controller.publish(transaction.toAttoTransaction(), request) }
                }
            val streamed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { controller.publishAndStream(transaction.toAttoTransaction(), request).first() }
                }

            // When
            controller.process(ElectionRejected(account, transaction))
            val failures = withTimeout(1.seconds) { listOf(published.await().exceptionOrNull(), streamed.await().exceptionOrNull()) }

            // Then
            failures.forEach {
                val failure = assertInstanceOf(ResponseStatusException::class.java, it)
                assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failure.statusCode)
                assertEquals("Transaction confirmation lookup failed", failure.reason)
                assertNull(failure.cause)
            }
        }

    @Test
    fun `late election rejection lookup cannot replace confirmation or complete a newer publication`() =
        runTest {
            // Given
            val repository = mockk<TransactionRepository>()
            val lookupStarted = CompletableDeferred<Unit>()
            val releaseLookup = CompletableDeferred<Unit>()
            coEvery { repository.findById(transaction.hash) } coAnswers {
                lookupStarted.complete(Unit)
                releaseLookup.await()
                null
            }
            val controller = TransactionPublicationController(ApplicationProperties(), node, mockk(relaxed = true), repository)
            val request =
                MockServerHttpRequest
                    .post("/transactions")
                    .remoteAddress(InetSocketAddress("127.0.0.1", 12345))
                    .build()
            val original =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.publish(transaction.toAttoTransaction(), request)
                }
            val rejection =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.process(ElectionRejected(account, transaction))
                }
            lookupStarted.await()

            // When
            controller.process(AccountUpdated(TransactionSource.ELECTION, account, account, transaction))
            val originalResult = withTimeout(1.seconds) { original.await() }
            val newer =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.publish(transaction.toAttoTransaction(), request)
                }
            releaseLookup.complete(Unit)
            rejection.await()

            // Then
            assertEquals(transaction.toAttoTransaction(), originalResult)
            assertFalse(newer.isCompleted)
            controller.process(AccountUpdated(TransactionSource.ELECTION, account, account, transaction))
            assertEquals(transaction.toAttoTransaction(), withTimeout(1.seconds) { newer.await() })
        }

    @Test
    fun `election rejection without publication waiters skips the database lookup`() =
        runTest {
            // Given
            val repository = mockk<TransactionRepository>()
            val controller = TransactionPublicationController(ApplicationProperties(), node, mockk(), repository)

            // When
            controller.process(ElectionRejected(account, transaction))

            // Then
            coVerify(exactly = 0) { repository.findById(any()) }
        }

    @Test
    fun `publish and publishAndStream fail promptly when transaction validation fails`() =
        runTest {
            // Given
            val applicationProperties =
                ApplicationProperties().apply {
                    useXForwardedFor = false
                }
            val messagePublisher = mockk<NetworkMessagePublisher>()
            val repository = mockk<TransactionRepository>()
            val publishSignals = Channel<Unit>(capacity = Channel.UNLIMITED)
            every { messagePublisher.publish(any()) } answers {
                publishSignals.trySend(Unit)
                Unit
            }
            val controller = TransactionPublicationController(applicationProperties, node, messagePublisher, repository)
            val request =
                MockServerHttpRequest
                    .post("/transactions/stream")
                    .remoteAddress(InetSocketAddress("127.0.0.1", 12345))
                    .build()
            val streamed =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { controller.publishAndStream(transaction.toAttoTransaction(), request).first() }
                }
            val published =
                async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { controller.publish(transaction.toAttoTransaction(), request) }
                }
            publishSignals.receive()
            publishSignals.receive()
            val cause = IllegalStateException("Database connection failed with private details")

            // When
            controller.process(TransactionValidationFailed(transaction, cause))
            val failures =
                withTimeout(1.seconds) {
                    listOf(streamed.await().exceptionOrNull(), published.await().exceptionOrNull())
                }

            // Then
            failures.forEach {
                val failure = assertInstanceOf(ResponseStatusException::class.java, it)
                assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failure.statusCode)
                assertEquals("Transaction validation failed", failure.reason)
                assertNull(failure.cause)
            }
            verify(exactly = 2) { messagePublisher.publish(any()) }
        }

    @Test
    fun `publishAndStream allows multiple listeners for the same transaction hash`() =
        runTest {
            val applicationProperties =
                ApplicationProperties().apply {
                    useXForwardedFor = false
                }
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            val publishSignals = Channel<Unit>(capacity = Channel.UNLIMITED)
            every { messagePublisher.publish(any()) } answers {
                publishSignals.trySend(Unit)
                Unit
            }

            val controller =
                TransactionPublicationController(
                    applicationProperties,
                    node,
                    messagePublisher,
                    repository,
                )
            val request =
                MockServerHttpRequest
                    .post("/transactions/stream")
                    .remoteAddress(InetSocketAddress("127.0.0.1", 12345))
                    .build()

            val firstListener =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.publishAndStream(transaction.toAttoTransaction(), request).first()
                }
            val secondListener =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.publishAndStream(transaction.toAttoTransaction(), request).first()
                }

            publishSignals.receive()
            publishSignals.receive()
            controller.process(AccountUpdated(TransactionSource.ELECTION, account, account, transaction))

            assertEquals(transaction.toAttoTransaction(), firstListener.await())
            assertEquals(transaction.toAttoTransaction(), secondListener.await())
            verify(exactly = 2) { messagePublisher.publish(any()) }
        }

    @Test
    fun `publishAndStream deduplicate listener joins pending confirmation without republishing`() =
        runTest {
            val applicationProperties =
                ApplicationProperties().apply {
                    useXForwardedFor = false
                }
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            val publishSignals = Channel<Unit>(capacity = Channel.UNLIMITED)
            coEvery { repository.findById(transaction.hash) } returns null
            every { messagePublisher.publish(any()) } answers {
                publishSignals.trySend(Unit)
                Unit
            }

            val controller =
                TransactionPublicationController(
                    applicationProperties,
                    node,
                    messagePublisher,
                    repository,
                )
            val request =
                MockServerHttpRequest
                    .post("/transactions/stream")
                    .remoteAddress(InetSocketAddress("127.0.0.1", 12345))
                    .build()

            val firstListener =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.publishAndStream(transaction.toAttoTransaction(), request).first()
                }
            val deduplicateListener =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.publishAndStream(transaction.toAttoTransaction(), request, deduplicate = true).first()
                }

            publishSignals.receive()
            controller.process(AccountUpdated(TransactionSource.ELECTION, account, account, transaction))

            assertEquals(transaction.toAttoTransaction(), firstListener.await())
            assertEquals(transaction.toAttoTransaction(), deduplicateListener.await())
            verify(exactly = 1) { messagePublisher.publish(any()) }
        }

    @Test
    fun `publish deduplicate returns existing confirmed transaction from repository before publishing`() =
        runTest {
            val applicationProperties =
                ApplicationProperties().apply {
                    useXForwardedFor = false
                }
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            coEvery { repository.findById(transaction.hash) } returns transaction

            val controller =
                TransactionPublicationController(
                    applicationProperties,
                    node,
                    messagePublisher,
                    repository,
                )
            val request =
                MockServerHttpRequest
                    .post("/transactions")
                    .remoteAddress(InetSocketAddress("127.0.0.1", 12345))
                    .build()

            val result = controller.publish(transaction.toAttoTransaction(), request, deduplicate = true)

            assertEquals(transaction.toAttoTransaction(), result)
            verify(exactly = 0) { messagePublisher.publish(any()) }
        }

    @Test
    fun `publish waits for confirmation response`() =
        runTest {
            val applicationProperties =
                ApplicationProperties().apply {
                    useXForwardedFor = false
                }
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            val publishSignals = Channel<Unit>(capacity = Channel.UNLIMITED)
            every { messagePublisher.publish(any()) } answers {
                publishSignals.trySend(Unit)
                Unit
            }

            val controller =
                TransactionPublicationController(
                    applicationProperties,
                    node,
                    messagePublisher,
                    repository,
                )
            val request =
                MockServerHttpRequest
                    .post("/transactions")
                    .remoteAddress(InetSocketAddress("127.0.0.1", 12345))
                    .build()

            val published =
                async(start = CoroutineStart.UNDISPATCHED) {
                    controller.publish(transaction.toAttoTransaction(), request)
                }

            publishSignals.receive()
            controller.process(AccountUpdated(TransactionSource.ELECTION, account, account, transaction))

            assertEquals(transaction.toAttoTransaction(), published.await())
            verify(exactly = 1) { messagePublisher.publish(any()) }
        }
}
