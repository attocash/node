package cash.atto.node.election

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.commons.toPublicKeyBlocking
import cash.atto.commons.worker.AttoWorker
import cash.atto.node.ApplicationConfiguration
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountService
import cash.atto.node.account.AccountUpdated
import cash.atto.node.account.entry.AccountEntryService
import cash.atto.node.executeAfterCommit
import cash.atto.node.getCurrentTransaction
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.receivable.ReceivableRepository
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.TransactionService
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.transaction.validation.TransactionValidationManager
import cash.atto.node.transaction.validation.validator.BlockValidator
import cash.atto.node.transaction.validation.validator.PreviousValidator
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.SimpleApplicationEventMulticaster
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

class ElectionBootstrapRecoveryTest {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    fun `bootstrap commit retires queued consensus while the next height continues through asynchronous events`(
        bootstrapAdvancesAgain: Boolean,
    ) = runBlocking {
        // Given
        val privateKey = AttoPrivateKey.generate()
        val initialAccount = account(privateKey.toPublicKeyBlocking())
        val head = receive(privateKey, initialAccount)
        val accountAtHead =
            initialAccount.copy(
                height = head.height.value.toLong(),
                balance = head.block.balance,
                lastTransactionHash = head.hash,
                lastTransactionTimestamp = head.block.timestamp.toJavaInstant(),
            )
        val bootstrapNext = receive(privateKey, accountAtHead)
        val bootstrapTransactions = if (bootstrapAdvancesAgain) listOf(head, bootstrapNext) else listOf(head)
        val liveAccount =
            if (bootstrapAdvancesAgain) {
                accountAtHead.copy(
                    height = bootstrapNext.height.value.toLong(),
                    balance = bootstrapNext.block.balance,
                    lastTransactionHash = bootstrapNext.hash,
                    lastTransactionTimestamp = bootstrapNext.block.timestamp.toJavaInstant(),
                )
            } else {
                accountAtHead
            }
        val next = if (bootstrapAdvancesAgain) receive(privateKey, liveAccount) else bootstrapNext
        val currentAccount = AtomicReference(initialAccount)
        val firstAccountRead = AtomicBoolean(true)
        val accountReadStarted = CompletableDeferred<Unit>()
        val releaseAccountRead = CompletableDeferred<Unit>()
        val recoveryQueries = CopyOnWriteArrayList<Set<AttoPublicKey>>()
        val persisted = CopyOnWriteArrayList<Transaction>()
        val committed = CopyOnWriteArrayList<AccountUpdated>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val headStarted = CompletableDeferred<Unit>()
        val nextStarted = CompletableDeferred<Unit>()
        val headConsensus = CompletableDeferred<ElectionConsensusReached>()
        val nextConsensus = CompletableDeferred<Unit>()
        val repository = mockk<AccountRepository>()
        coEvery { repository.findById(any()) } coAnswers {
            (getCurrentTransaction()?.getResource(firstArg<AttoPublicKey>()) as? Account) ?: currentAccount.get()
        }
        every { repository.findAllById(any()) } answers {
            val publicKeys = firstArg<Iterable<AttoPublicKey>>().toSet()
            flow {
                val currentTransaction = getCurrentTransaction()
                if (currentTransaction == null) recoveryQueries.add(publicKeys)
                if (firstAccountRead.compareAndSet(true, false)) {
                    accountReadStarted.complete(Unit)
                    releaseAccountRead.await()
                }
                val account =
                    (currentTransaction?.getResource(initialAccount.publicKey) as? Account) ?: currentAccount.get()
                account.takeIf { it.publicKey in publicKeys }?.let { emit(it) }
            }
        }
        every { repository.saveAll(any()) } answers {
            val accounts = firstArg<List<Account>>()
            flow {
                val saved = accounts.map { it.copy(height = it.height + 1, updatedAt = Instant.now()) }
                val transaction = getCurrentTransaction()!!
                saved.forEach {
                    transaction.unbindResourceIfPossible(it.publicKey)
                    transaction.bindResource(it.publicKey, it)
                }
                executeAfterCommit { saved.forEach(currentAccount::set) }
                saved.forEach { emit(it) }
            }
        }
        val transactionService = mockk<TransactionService>()
        coEvery { transactionService.saveAll(any()) } coAnswers {
            val transactions = firstArg<List<Transaction>>().toList()
            executeAfterCommit { persisted.addAll(transactions) }
        }
        val transactionManager = TestTransactionManager()
        val node = node()

        AnnotationConfigApplicationContext().use { context ->
            val publisher = EventPublisher(context)
            val validation =
                TransactionValidationManager(repository, listOf(BlockValidator(node), PreviousValidator()), publisher)
            val weights = mockk<VoteWeighter>()
            every { weights.getMinimalConfirmationWeight() } returns AttoAmount(1_000UL)
            every { weights.get(any()) } returns AttoAmount(1_000UL)
            val election =
                Election(
                    properties = ElectionProperties(),
                    voteWeighter = weights,
                    eventPublisher = publisher,
                    meterRegistry = SimpleMeterRegistry(),
                )
            val accountService =
                AccountService(
                    node,
                    repository,
                    mockk<AccountEntryService>(),
                    transactionService,
                    mockk<ReceivableRepository>(),
                    publisher,
                )
            val processor =
                ElectionProcessor(
                    messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true),
                    accountService = accountService,
                    accountRepository = repository,
                    meterRegistry = SimpleMeterRegistry(),
                    transactionManager = transactionManager,
                ).also { it.start() }
            val multicaster = ApplicationConfiguration().applicationEventMulticaster() as SimpleApplicationEventMulticaster
            multicaster.setErrorHandler { errors.add(it) }
            context.beanFactory.registerSingleton("applicationEventMulticaster", multicaster)
            context.beanFactory.registerSingleton("election", election)
            context.beanFactory.registerSingleton("electionProcessor", processor)
            context.beanFactory.registerSingleton("transactionValidationManager", validation)
            context.addApplicationListener(
                ApplicationListener<ApplicationEvent> { event ->
                    when (val payload = (event as? PayloadApplicationEvent<*>)?.payload) {
                        is ElectionStarted -> {
                            when (payload.transaction.hash) {
                                head.hash -> headStarted.complete(Unit)
                                next.hash -> nextStarted.complete(Unit)
                            }
                        }

                        is ElectionConsensusReached -> {
                            when (payload.transaction.hash) {
                                head.hash -> headConsensus.complete(payload)
                                next.hash -> nextConsensus.complete(Unit)
                            }
                        }

                        is AccountUpdated -> {
                            committed.add(payload)
                        }
                    }
                },
            )
            context.refresh()

            try {
                // When
                publisher.publish(TransactionReceived(head))
                withTimeout(5_000) { headStarted.await() }
                publisher.publish(VoteValidated(head, vote(head)))
                withTimeout(5_000) { accountReadStarted.await() }
                TransactionalOperator.create(transactionManager).executeAndAwait {
                    bootstrapTransactions.forEach { accountService.add(TransactionSource.BOOTSTRAP, listOf(it)) }
                }
                publisher.publish(withTimeout(5_000) { headConsensus.await() })
                publisher.publish(TransactionReceived(next))
                withTimeout(5_000) { nextStarted.await() }
                publisher.publish(VoteValidated(next, vote(next)))
                withTimeout(5_000) { nextConsensus.await() }
                releaseAccountRead.complete(Unit)

                // Then
                await().atMost(5, TimeUnit.SECONDS).untilAsserted {
                    assertEquals((bootstrapTransactions + next).map { it.hash }, persisted.map { it.hash })
                    assertEquals(next.height.value.toLong(), currentAccount.get().height)
                    assertEquals(0, processor.getBufferSize())
                    assertEquals(
                        listOf(next.hash),
                        committed.filter { it.source == TransactionSource.ELECTION }.map { it.transaction.hash },
                    )
                    assertTrue(errors.isEmpty())
                    assertEquals(1, transactionManager.rollbacks.get())
                    assertEquals(listOf(setOf(initialAccount.publicKey)), recoveryQueries.toList())
                }
            } finally {
                releaseAccountRead.complete(Unit)
                processor.stop()
            }
        }
    }

    private fun account(publicKey: AttoPublicKey): Account =
        Account(
            publicKey = publicKey,
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            height = 1,
            balance = AttoAmount(100UL),
            lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT.toJavaInstant(),
            lastTransactionHash = AttoHash(Random.nextBytes(32)),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = AttoPublicKey(Random.nextBytes(32)),
            persistedAt = Instant.now(),
        )

    private suspend fun receive(
        privateKey: AttoPrivateKey,
        account: Account,
    ): Transaction {
        val block =
            AttoReceiveBlock(
                version = account.version,
                network = account.network,
                algorithm = account.algorithm,
                publicKey = account.publicKey,
                height = AttoHeight((account.height + 1).toULong()),
                balance = AttoAmount(account.balance.raw + 100UL),
                timestamp = account.lastTransactionTimestamp.plusSeconds(1).toAtto(),
                previous = account.lastTransactionHash,
                sendHashAlgorithm = AttoAlgorithm.V1,
                sendHash = AttoHash(Random.nextBytes(32)),
            )
        return Transaction(block, privateKey.sign(block.hash), AttoWorker.cpu().work(block))
    }

    private fun vote(transaction: Transaction): Vote =
        Vote(
            hash = AttoHash(Random.nextBytes(32)),
            version = transaction.block.version,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(32)),
            blockAlgorithm = transaction.algorithm,
            blockHash = transaction.hash,
            timestamp = Instant.now(),
            signature = AttoSignature(Random.nextBytes(64)),
            weight = AttoAmount(1_000UL),
        )

    private fun node(): AttoNode =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0U,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(32)),
            publicUri = URI("ws://localhost:8081"),
            features = setOf(NodeFeature.VOTING),
        )

    private class TestTransactionManager : AbstractReactiveTransactionManager() {
        val rollbacks = AtomicInteger()

        override fun doGetTransaction(synchronizationManager: TransactionSynchronizationManager): Any = Any()

        override fun doBegin(
            synchronizationManager: TransactionSynchronizationManager,
            transaction: Any,
            definition: TransactionDefinition,
        ): Mono<Void> = Mono.empty()

        override fun doCommit(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> = Mono.empty()

        override fun doRollback(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> = Mono.fromRunnable { rollbacks.incrementAndGet() }
    }
}
