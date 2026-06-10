package cash.atto.node.election

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoVersion
import cash.atto.node.account.AccountService
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.vote.VoteService
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import reactor.core.publisher.Mono
import java.net.URI
import kotlin.random.Random

class ElectionProcessorTest {
    @Test
    fun `keeps consensus events queued when persistence fails`() =
        runBlocking {
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val processor = newProcessor(accountService, transactionManager)
            val transactions =
                listOf(
                    Transaction.sample(),
                    Transaction.sample(),
                )
            var attempts = 0

            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                attempts++
                if (attempts == 1) {
                    throw IllegalStateException("db down")
                }
                emptyList()
            }

            transactions.forEach {
                processor.process(ElectionConsensusReached(mockk(relaxed = true), it, emptyList()))
            }

            assertThrows<RuntimeException> {
                runBlocking {
                    processor.flush()
                }
            }

            assertEquals(2, processor.getBufferSize())
            assertEquals(1, attempts)
            assertEquals(0, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)

            processor.flush()

            assertEquals(0, processor.getBufferSize())
            assertEquals(2, attempts)
            assertEquals(1, transactionManager.commits)
            assertEquals(1, transactionManager.rollbacks)
        }

    @Test
    fun `persists drained consensus events in one batch`() =
        runBlocking {
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val processor = newProcessor(accountService, transactionManager)
            val firstTransaction = Transaction.sample()
            val secondTransaction = Transaction.sample()
            val savedBatches = mutableListOf<List<Transaction>>()

            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                savedBatches += secondArg<List<Transaction>>()
                emptyList()
            }

            processor.process(ElectionConsensusReached(mockk(relaxed = true), firstTransaction, emptyList()))
            processor.process(ElectionConsensusReached(mockk(relaxed = true), secondTransaction, emptyList()))

            processor.flush()

            assertEquals(0, processor.getBufferSize())
            assertEquals(
                listOf(
                    listOf(firstTransaction.hash, secondTransaction.hash),
                ),
                savedBatches.map { batch -> batch.map { it.hash } },
            )
            assertEquals(1, transactionManager.commits)
            assertEquals(0, transactionManager.rollbacks)
        }

    private fun newProcessor(
        accountService: AccountService,
        transactionManager: ReactiveTransactionManager,
    ): ElectionProcessor =
        ElectionProcessor(
            thisNode = sampleNode(),
            messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true),
            accountService = accountService,
            voteService = mockk<VoteService>(relaxed = true),
            meterRegistry = SimpleMeterRegistry(),
            transactionManager = transactionManager,
        ).also { it.start() }

    private class RecordingReactiveTransactionManager : ReactiveTransactionManager {
        var commits = 0
            private set
        var rollbacks = 0
            private set

        override fun getReactiveTransaction(definition: TransactionDefinition?): Mono<ReactiveTransaction> =
            Mono.just(SimpleReactiveTransaction)

        override fun commit(transaction: ReactiveTransaction): Mono<Void> = Mono.fromRunnable<Void> { commits++ }

        override fun rollback(transaction: ReactiveTransaction): Mono<Void> = Mono.fromRunnable<Void> { rollbacks++ }
    }

    private object SimpleReactiveTransaction : ReactiveTransaction

    private fun sampleNode(): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0U.toUShort(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            publicUri = URI("ws://127.0.0.1:8081"),
            features = setOf(NodeFeature.VOTING),
        )

    private fun Transaction.Companion.sample(
        publicKey: AttoPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        height: AttoHeight = AttoHeight(2UL),
    ): Transaction =
        Transaction(
            block =
                AttoReceiveBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    height = height,
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = AttoHash(Random.nextBytes(ByteArray(32))),
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
                ),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
        )
}
