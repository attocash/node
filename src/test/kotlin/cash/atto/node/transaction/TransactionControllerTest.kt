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
import cash.atto.commons.sign
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.commons.toPublicKey
import cash.atto.commons.worker.AttoWorker
import cash.atto.commons.worker.cpu
import cash.atto.node.ApplicationProperties
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountUpdated
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import java.net.InetSocketAddress
import java.net.URI
import kotlin.random.Random

internal class TransactionControllerTest {
    private val privateKey = AttoPrivateKey.generate()
    private val block =
        AttoReceiveBlock(
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
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

    @Test
    fun `publishAndStream allows multiple listeners for the same transaction hash`() =
        runTest {
            val applicationProperties =
                ApplicationProperties().apply {
                    useXForwardedFor = false
                }
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            val publishSignals = Channel<Unit>(capacity = Channel.UNLIMITED)
            every { messagePublisher.publish(any()) } answers {
                publishSignals.trySend(Unit)
                Unit
            }

            val controller =
                TransactionController(
                    applicationProperties,
                    node,
                    eventPublisher,
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
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            val publishSignals = Channel<Unit>(capacity = Channel.UNLIMITED)
            coEvery { repository.findById(transaction.hash) } returns null
            every { messagePublisher.publish(any()) } answers {
                publishSignals.trySend(Unit)
                Unit
            }

            val controller =
                TransactionController(
                    applicationProperties,
                    node,
                    eventPublisher,
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
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            coEvery { repository.findById(transaction.hash) } returns transaction

            val controller =
                TransactionController(
                    applicationProperties,
                    node,
                    eventPublisher,
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
            val eventPublisher = mockk<EventPublisher>(relaxed = true)
            val messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val repository = mockk<TransactionRepository>()
            val publishSignals = Channel<Unit>(capacity = Channel.UNLIMITED)
            every { messagePublisher.publish(any()) } answers {
                publishSignals.trySend(Unit)
                Unit
            }

            val controller =
                TransactionController(
                    applicationProperties,
                    node,
                    eventPublisher,
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
