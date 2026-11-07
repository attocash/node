package cash.atto.node.transaction

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.commons.toPublicKeyBlocking
import cash.atto.commons.worker.AttoWorker
import cash.atto.node.ApplicationConfiguration
import cash.atto.node.ApplicationProperties
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.Election
import cash.atto.node.election.ElectionProperties
import cash.atto.node.election.ElectionRejected
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.guardian.ExpectedResponses
import cash.atto.node.transaction.prioritization.TransactionPrioritizationProperties
import cash.atto.node.transaction.prioritization.TransactionPrioritizer
import cash.atto.node.transaction.validation.TransactionValidationManager
import cash.atto.node.transaction.validation.TransactionValidator
import cash.atto.node.transaction.validation.TransactionViolation
import cash.atto.node.transaction.validation.validator.BlockValidator
import cash.atto.node.transaction.validation.validator.PreviousValidator
import cash.atto.node.transaction.validation.validator.SendValidator
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.time.Duration.Companion.seconds

internal class TransactionControllerElectionRejectionTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `publication fails promptly when validated competitor arrives after winner completion`(stream: Boolean) =
        runBlocking {
            // Given
            val privateKey = AttoPrivateKey.generate()
            val account =
                Account(
                    publicKey = privateKey.toPublicKeyBlocking(),
                    network = AttoNetwork.LOCAL,
                    version = 0U.toAttoVersion(),
                    algorithm = AttoAlgorithm.V1,
                    height = 1,
                    balance = AttoAmount(100UL),
                    lastTransactionHash = AttoHash(Random.nextBytes(32)),
                    lastTransactionTimestamp = Instant.now().minusSeconds(10),
                    representativeAlgorithm = AttoAlgorithm.V1,
                    representativePublicKey = AttoPublicKey(Random.nextBytes(32)),
                )
            val candidate = send(privateKey, account)
            val winner = send(privateKey, account)
            val winnerAccount =
                account.copy(
                    height = winner.height.value.toLong(),
                    balance = winner.block.balance,
                    lastTransactionHash = winner.hash,
                    lastTransactionTimestamp = winner.block.timestamp.toJavaInstant(),
                )
            val node =
                AttoNode(
                    network = account.network,
                    protocolVersion = 0U,
                    algorithm = account.algorithm,
                    publicKey = AttoPublicKey(Random.nextBytes(32)),
                    publicUri = URI("ws://localhost:8081"),
                    features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL),
                )
            val accountRepository = mockk<AccountRepository>()
            coEvery { accountRepository.findById(account.publicKey) } returns account
            val repository = mockk<TransactionRepository>()
            coEvery { repository.findById(candidate.hash) } returns null
            val validationPassed = CompletableDeferred<Unit>()
            val releaseValidation = CompletableDeferred<Unit>()
            val delayValidation =
                object : TransactionValidator {
                    override fun supports(transaction: Transaction): Boolean = true

                    override suspend fun validate(
                        account: Account,
                        transaction: Transaction,
                    ): TransactionViolation? {
                        validationPassed.complete(Unit)
                        releaseValidation.await()
                        return null
                    }
                }
            val errors = CopyOnWriteArrayList<Throwable>()
            val rejected = CopyOnWriteArrayList<ElectionRejected>()
            val registry = SimpleMeterRegistry()
            try {
                AnnotationConfigApplicationContext().use { context ->
                    val publisher = EventPublisher(context)
                    val election = Election(ElectionProperties(), mockk(), publisher, registry)
                    val prioritizer =
                        TransactionPrioritizer(
                            TransactionPrioritizationProperties().apply {
                                groupMaxSize = 10
                                maxActiveElections = 1
                            },
                            publisher,
                            registry,
                        ).also { it.start() }
                    val validation =
                        TransactionValidationManager(
                            accountRepository,
                            listOf(BlockValidator(node), PreviousValidator(), SendValidator(), delayValidation),
                            publisher,
                        )
                    val controller =
                        TransactionPublicationController(
                            ApplicationProperties(),
                            node,
                            NetworkMessagePublisher(context, ExpectedResponses(Clock.systemUTC())),
                            repository,
                        )
                    val multicaster = ApplicationConfiguration().applicationEventMulticaster() as SimpleApplicationEventMulticaster
                    multicaster.setErrorHandler { errors += it }
                    context.beanFactory.registerSingleton("applicationEventMulticaster", multicaster)
                    context.beanFactory.registerSingleton("transactionPublicationController", controller)
                    context.beanFactory.registerSingleton("transactionPrioritizer", prioritizer)
                    context.beanFactory.registerSingleton("transactionValidationManager", validation)
                    context.beanFactory.registerSingleton("election", election)
                    context.addApplicationListener(
                        ApplicationListener<ApplicationEvent> { event ->
                            val payload = (event as? PayloadApplicationEvent<*>)?.payload
                            if (payload is ElectionRejected) rejected += payload
                        },
                    )
                    context.refresh()
                    val request =
                        MockServerHttpRequest
                            .post(if (stream) "/transactions/stream" else "/transactions")
                            .remoteAddress(InetSocketAddress("127.0.0.1", 12345))
                            .build()
                    val result =
                        async(start = CoroutineStart.UNDISPATCHED) {
                            runCatching {
                                if (stream) {
                                    controller.publishAndStream(candidate.toAttoTransaction(), request).first()
                                } else {
                                    controller.publish(candidate.toAttoTransaction(), request)
                                }
                            }
                        }
                    try {
                        withTimeout(5.seconds) { validationPassed.await() }

                        // When
                        // Finish the winner's after-commit listener before the delayed validated event can be delivered.
                        election.process(AccountUpdated(TransactionSource.ELECTION, account, winnerAccount, winner))
                        releaseValidation.complete(Unit)
                        val failure = withTimeout(5.seconds) { result.await().exceptionOrNull() }

                        // Then
                        assertEquals(HttpStatus.CONFLICT, assertInstanceOf(ResponseStatusException::class.java, failure).statusCode)
                        await().atMost(5, TimeUnit.SECONDS).until {
                            rejected.size == 1 &&
                                registry.get("transactions.prioritizer.pending.dependencies").gauge().value() == 0.0
                        }
                        assertEquals(candidate.hash, rejected.single().transaction.hash)
                        assertEquals(0, election.getSize())
                        assertTrue(errors.isEmpty(), errors.toString())
                        coVerify(exactly = 1) { repository.findById(candidate.hash) }
                    } finally {
                        releaseValidation.complete(Unit)
                        result.cancel()
                        prioritizer.stop()
                    }
                }
            } finally {
                registry.close()
            }
        }

    private suspend fun send(
        privateKey: AttoPrivateKey,
        account: Account,
    ): Transaction {
        val block =
            AttoSendBlock(
                version = account.version,
                network = account.network,
                algorithm = account.algorithm,
                publicKey = account.publicKey,
                height = AttoHeight((account.height + 1).toULong()),
                balance = AttoAmount(account.balance.raw - 10UL),
                timestamp = account.lastTransactionTimestamp.plusSeconds(1).toAtto(),
                previous = account.lastTransactionHash,
                receiverAlgorithm = AttoAlgorithm.V1,
                receiverPublicKey = AttoPublicKey(Random.nextBytes(32)),
                amount = AttoAmount(10UL),
            )
        return Transaction(block, privateKey.sign(block.hash), AttoWorker.cpu().work(block))
    }
}
