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
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.account.AccountService
import cash.atto.node.account.AccountUpdated
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import io.micrometer.core.instrument.MockClock
import io.micrometer.core.instrument.simple.SimpleConfig
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.core.Ordered
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.reactive.AbstractReactiveTransactionManager
import org.springframework.transaction.reactive.GenericReactiveTransaction
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds

class ElectionProcessorTest {
    @Test
    fun `worker persists queued consensus`() =
        runTest {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val transaction = Transaction.sample()
            val queriedPublicKeys = CopyOnWriteArrayList<List<AttoPublicKey>>()
            val accountRepository = mockk<AccountRepository>()
            every { accountRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                queriedPublicKeys += firstArg<Iterable<AttoPublicKey>>().toList()
                emptyFlow()
            }
            val processor = newProcessor(accountService, transactionManager, accountRepository = accountRepository)
            val attempts = AtomicInteger()
            coEvery { accountService.add(TransactionSource.ELECTION, listOf(transaction)) } coAnswers {
                attempts.incrementAndGet()
                emptyList()
            }

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), transaction, emptyList()))

                // Then
                awaitCondition { transactionManager.commits == 1 && processor.getBufferSize() == 0 }
                assertEquals(0, processor.getBufferSize())
                assertEquals(1, attempts.get())
                assertEquals(emptyList<List<AttoPublicKey>>(), queriedPublicKeys.toList())
            } finally {
                processor.stop()
            }
        }

    @Test
    fun `worker requeues and retries consensus when persistence fails`() =
        runTest {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val meterRegistry = SimpleMeterRegistry()
            val transactions = listOf(Transaction.sample(), Transaction.sample())
            val queriedPublicKeys = CopyOnWriteArrayList<List<AttoPublicKey>>()
            val accountRepository = mockk<AccountRepository>()
            every { accountRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                queriedPublicKeys += firstArg<Iterable<AttoPublicKey>>().toList()
                emptyFlow()
            }
            val processor =
                newProcessor(
                    accountService,
                    transactionManager,
                    meterRegistry,
                    accountRepository,
                )
            val attempts = AtomicInteger()
            val batches = CopyOnWriteArrayList<List<Transaction>>()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val releaseFirstAttempt = CompletableDeferred<Unit>()
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                val batch = secondArg<List<Transaction>>()
                batches += batch
                if (attempts.incrementAndGet() == 1) {
                    firstAttemptStarted.complete(Unit)
                    releaseFirstAttempt.await()
                    throw IllegalStateException("db down")
                }
                emptyList()
            }

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), transactions.first(), emptyList()))
                firstAttemptStarted.await()
                processor.process(ElectionConsensusReached(mockk(relaxed = true), transactions.last(), emptyList()))
                releaseFirstAttempt.complete(Unit)

                // Then
                awaitCondition {
                    transactionManager.commits == 1 &&
                        transactionManager.rollbacks == 1 &&
                        processor.getBufferSize() == 0 &&
                        meterRegistry.get("elections.processor.batch").timer().count() == 1L
                }
                assertEquals(2, attempts.get())
                assertEquals(0, processor.getBufferSize())
                assertPhaseCounts(meterRegistry, 1)
                assertEquals(listOf(listOf(transactions.first()), transactions), batches.toList())
                assertEquals(listOf(listOf(transactions.first().publicKey)), queriedPublicKeys.toList())
            } finally {
                processor.stop()
            }
        }

    @Test
    fun `deduplicates an identical queued in flight and retried consensus hash`() =
        runTest {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val previousAccount = sampleAccount(publicKey, AttoHash(Random.nextBytes(ByteArray(32))), height = 1)
            val transaction =
                Transaction.sample(
                    publicKey = publicKey,
                    height = AttoHeight(2UL),
                    previous = previousAccount.lastTransactionHash,
                )
            val event = ElectionConsensusReached(previousAccount, transaction, emptyList())
            val queriedPublicKeys = CopyOnWriteArrayList<List<AttoPublicKey>>()
            val accountRepository = mockk<AccountRepository>()
            every { accountRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                queriedPublicKeys += firstArg<Iterable<AttoPublicKey>>().toList()
                emptyFlow()
            }
            val processor = newProcessor(accountService, transactionManager, accountRepository = accountRepository)
            val attempts = AtomicInteger()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val releaseFirstAttempt = CompletableDeferred<Unit>()
            val retryStarted = CompletableDeferred<Unit>()
            val releaseRetry = CompletableDeferred<Unit>()
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                when (attempts.incrementAndGet()) {
                    1 -> {
                        firstAttemptStarted.complete(Unit)
                        releaseFirstAttempt.await()
                        throw IllegalStateException("election persistence failure")
                    }

                    2 -> {
                        retryStarted.complete(Unit)
                        releaseRetry.await()
                    }
                }
                emptyList()
            }

            try {
                // When
                processor.process(event)
                assertEquals(1, processor.getBufferSize())
                processor.process(event)
                assertEquals(1, processor.getBufferSize())
                firstAttemptStarted.await()
                processor.process(event)
                assertEquals(1, processor.getBufferSize())
                releaseFirstAttempt.complete(Unit)
                retryStarted.await()
                processor.process(event)
                assertEquals(1, processor.getBufferSize())
                releaseRetry.complete(Unit)

                // Then
                awaitCondition {
                    transactionManager.commits == 1 &&
                        transactionManager.rollbacks == 1 &&
                        processor.getBufferSize() == 0
                }
                assertEquals(2, attempts.get())
                coVerify(exactly = 2) { accountService.add(TransactionSource.ELECTION, listOf(transaction)) }
                assertEquals(listOf(listOf(transaction.publicKey)), queriedPublicKeys.toList())
            } finally {
                releaseFirstAttempt.complete(Unit)
                releaseRetry.complete(Unit)
                processor.stop()
            }
        }

    @Test
    fun `preserves FIFO hashes and splits a batch before a repeated public key`() =
        runTest {
            // Given
            val blockerKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val repeatedKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val firstUnrelatedKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val secondUnrelatedKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val blockerHead = sampleAccount(blockerKey, AttoHash(Random.nextBytes(ByteArray(32))), height = 1)
            val firstHead = sampleAccount(repeatedKey, AttoHash(Random.nextBytes(ByteArray(32))), height = 1)
            val staleTransaction =
                Transaction.sample(
                    publicKey = repeatedKey,
                    height = AttoHeight(2UL),
                    previous = firstHead.lastTransactionHash,
                )
            val updatedFirstHead =
                firstHead.copy(
                    height = 2,
                    lastTransactionHash = staleTransaction.hash,
                    lastTransactionTimestamp = Instant.ofEpochMilli(staleTransaction.block.timestamp.toEpochMilliseconds()),
                )
            val laterTransaction =
                Transaction.sample(
                    publicKey = repeatedKey,
                    height = AttoHeight(3UL),
                    previous = staleTransaction.hash,
                    timestamp = staleTransaction.block.timestamp + 1.milliseconds,
                )
            val firstUnrelatedHead = sampleAccount(firstUnrelatedKey, AttoHash(Random.nextBytes(ByteArray(32))), height = 1)
            val secondUnrelatedHead = sampleAccount(secondUnrelatedKey, AttoHash(Random.nextBytes(ByteArray(32))), height = 1)
            val firstUnrelatedTransaction =
                Transaction.sample(
                    publicKey = firstUnrelatedKey,
                    height = AttoHeight(2UL),
                    previous = firstUnrelatedHead.lastTransactionHash,
                )
            val secondUnrelatedTransaction =
                Transaction.sample(
                    publicKey = secondUnrelatedKey,
                    height = AttoHeight(2UL),
                    previous = secondUnrelatedHead.lastTransactionHash,
                )
            val blockerTransaction =
                Transaction.sample(
                    publicKey = blockerKey,
                    height = AttoHeight(2UL),
                    previous = blockerHead.lastTransactionHash,
                )
            val savedAccounts = ConcurrentHashMap<AttoPublicKey, Account>()
            val queriedPublicKeys = CopyOnWriteArrayList<List<AttoPublicKey>>()
            val accountRepository = mockk<AccountRepository>()
            every { accountRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                val publicKeys = firstArg<Iterable<AttoPublicKey>>().toList()
                queriedPublicKeys += publicKeys
                publicKeys.mapNotNull(savedAccounts::get).asFlow()
            }
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val batches = CopyOnWriteArrayList<List<Transaction>>()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val releaseFirstAttempt = CompletableDeferred<Unit>()
            val secondBatchStarted = CompletableDeferred<Unit>()
            val releaseSecondBatch = CompletableDeferred<Unit>()
            val retryStarted = CompletableDeferred<Unit>()
            val releaseRetry = CompletableDeferred<Unit>()
            val attempts = AtomicInteger()
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                val batch = secondArg<List<Transaction>>()
                batches += batch
                when (attempts.incrementAndGet()) {
                    1 -> {
                        firstAttemptStarted.complete(Unit)
                        releaseFirstAttempt.await()
                    }

                    2 -> {
                        secondBatchStarted.complete(Unit)
                        releaseSecondBatch.await()
                        throw IllegalStateException("transaction already persisted")
                    }

                    3 -> {
                        retryStarted.complete(Unit)
                        releaseRetry.await()
                    }
                }
                emptyList()
            }
            val processor = newProcessor(accountService, transactionManager, accountRepository = accountRepository)

            try {
                // When
                processor.process(ElectionConsensusReached(blockerHead, blockerTransaction, emptyList()))
                firstAttemptStarted.await()
                processor.process(ElectionConsensusReached(firstHead, staleTransaction, emptyList()))
                processor.process(ElectionConsensusReached(firstUnrelatedHead, firstUnrelatedTransaction, emptyList()))
                // Bootstrap commits H2 before the valid H3 consensus is delivered.
                savedAccounts[repeatedKey] = updatedFirstHead
                processor.process(ElectionConsensusReached(updatedFirstHead, laterTransaction, emptyList()))
                processor.process(ElectionConsensusReached(secondUnrelatedHead, secondUnrelatedTransaction, emptyList()))
                assertEquals(5, processor.getBufferSize())
                releaseFirstAttempt.complete(Unit)
                secondBatchStarted.await()

                // Then
                awaitCondition { processor.getBufferSize() == 4 }
                assertEquals(emptyList<List<AttoPublicKey>>(), queriedPublicKeys.toList())
                assertEquals(4, processor.getBufferSize())
                processor.process(
                    AccountUpdated(
                        TransactionSource.BOOTSTRAP,
                        firstHead,
                        updatedFirstHead,
                        staleTransaction,
                    ),
                )
                assertEquals(3, processor.getBufferSize())
                releaseSecondBatch.complete(Unit)
                retryStarted.await()
                assertEquals(3, processor.getBufferSize())
                assertEquals(
                    listOf(
                        listOf(blockerTransaction),
                        listOf(staleTransaction, firstUnrelatedTransaction),
                        listOf(firstUnrelatedTransaction, laterTransaction, secondUnrelatedTransaction),
                    ),
                    batches.toList(),
                )
                assertEquals(
                    listOf(listOf(repeatedKey, firstUnrelatedKey)),
                    queriedPublicKeys.toList(),
                )
                releaseRetry.complete(Unit)

                awaitCondition {
                    transactionManager.commits == 2 &&
                        transactionManager.rollbacks == 1 &&
                        processor.getBufferSize() == 0
                }
            } finally {
                releaseFirstAttempt.complete(Unit)
                releaseSecondBatch.complete(Unit)
                releaseRetry.complete(Unit)
                processor.stop()
            }
        }

    @Test
    fun `retires older consensus when the account head has advanced`() =
        runTest {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val previousAccount = sampleAccount(publicKey, AttoHash(Random.nextBytes(ByteArray(32))), height = 1)
            val transaction =
                Transaction.sample(
                    publicKey = publicKey,
                    height = AttoHeight(2UL),
                    previous = previousAccount.lastTransactionHash,
                )
            val updatedAccount =
                previousAccount.copy(
                    height = 2,
                    lastTransactionHash = transaction.hash,
                    lastTransactionTimestamp = Instant.ofEpochMilli(transaction.block.timestamp.toEpochMilliseconds()),
                )
            val headTransaction =
                Transaction.sample(
                    publicKey = publicKey,
                    height = AttoHeight(3UL),
                    previous = transaction.hash,
                    timestamp = transaction.block.timestamp + 1.milliseconds,
                )
            val currentHead =
                updatedAccount.copy(
                    height = 3,
                    lastTransactionHash = headTransaction.hash,
                    lastTransactionTimestamp = Instant.ofEpochMilli(headTransaction.block.timestamp.toEpochMilliseconds()),
                )
            val laterPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val laterHead = sampleAccount(laterPublicKey, AttoHash(Random.nextBytes(ByteArray(32))), height = 1)
            val laterTransaction =
                Transaction.sample(
                    publicKey = laterPublicKey,
                    height = AttoHeight(2UL),
                    previous = laterHead.lastTransactionHash,
                )
            val savedAccounts = mapOf(publicKey to currentHead)
            val queriedPublicKeys = CopyOnWriteArrayList<List<AttoPublicKey>>()
            val accountRepository = mockk<AccountRepository>()
            every { accountRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                val publicKeys = firstArg<Iterable<AttoPublicKey>>().toList()
                queriedPublicKeys += publicKeys
                publicKeys.mapNotNull(savedAccounts::get).asFlow()
            }
            val meterRegistry = SimpleMeterRegistry()
            val attempts = AtomicInteger()
            val batches = CopyOnWriteArrayList<List<Transaction>>()
            val staleAttemptStarted = CompletableDeferred<Unit>()
            val releaseStaleAttempt = CompletableDeferred<Unit>()
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                val batch = secondArg<List<Transaction>>()
                batches += batch
                if (attempts.incrementAndGet() == 1) {
                    staleAttemptStarted.complete(Unit)
                    releaseStaleAttempt.await()
                    throw IllegalStateException("transaction is already persisted")
                }
                emptyList()
            }
            val processor =
                newProcessor(
                    accountService,
                    transactionManager,
                    meterRegistry = meterRegistry,
                    accountRepository = accountRepository,
                )

            try {
                // When
                processor.process(
                    AccountUpdated(TransactionSource.BOOTSTRAP, updatedAccount, currentHead, headTransaction),
                )
                processor.process(ElectionConsensusReached(previousAccount, transaction, emptyList()))
                staleAttemptStarted.await()
                processor.process(ElectionConsensusReached(laterHead, laterTransaction, emptyList()))
                releaseStaleAttempt.complete(Unit)

                // Then
                awaitCondition {
                    transactionManager.rollbacks == 1 &&
                        processor.getBufferSize() == 0 &&
                        queriedPublicKeys.size == 1 &&
                        meterRegistry.get("elections.processor.batch").timer().count() == 2L
                }
                assertEquals(1, transactionManager.commits)
                assertEquals(
                    listOf(listOf(transaction), listOf(laterTransaction)),
                    batches.toList(),
                )
                assertEquals(listOf(listOf(transaction.publicKey)), queriedPublicKeys.toList())
                assertPhaseCounts(meterRegistry, 1)
            } finally {
                releaseStaleAttempt.complete(Unit)
                processor.stop()
            }
        }

    @Test
    fun `retires obsolete height after commit failure and continues with later work`() =
        runTest {
            // Given
            val commitAttempts = AtomicInteger()
            val accountService = mockk<AccountService>()
            val firstPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val laterPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
            val firstHead = sampleAccount(firstPublicKey, AttoHash(Random.nextBytes(ByteArray(32))), height = 1)
            val laterHead = sampleAccount(laterPublicKey, AttoHash(Random.nextBytes(ByteArray(32))), height = 1)
            val firstTransaction =
                Transaction.sample(
                    publicKey = firstPublicKey,
                    height = AttoHeight(2UL),
                    previous = firstHead.lastTransactionHash,
                )
            val laterTransaction =
                Transaction.sample(
                    publicKey = laterPublicKey,
                    height = AttoHeight(2UL),
                    previous = laterHead.lastTransactionHash,
                )
            val savedAccounts = ConcurrentHashMap<AttoPublicKey, Account>()
            val queriedPublicKeys = CopyOnWriteArrayList<List<AttoPublicKey>>()
            val accountRepository = mockk<AccountRepository>()
            every { accountRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                val publicKeys = firstArg<Iterable<AttoPublicKey>>().toList()
                queriedPublicKeys += publicKeys
                publicKeys.mapNotNull(savedAccounts::get).asFlow()
            }
            val transactionManagerWithBootstrapRace =
                RecordingReactiveTransactionManager(
                    onCommit = {
                        if (commitAttempts.incrementAndGet() == 1) {
                            // A separate bootstrap commit advances this account while election persistence fails.
                            savedAccounts[firstPublicKey] =
                                firstHead.copy(
                                    height = 2,
                                    lastTransactionHash = firstTransaction.hash,
                                    lastTransactionTimestamp =
                                        Instant.ofEpochMilli(firstTransaction.block.timestamp.toEpochMilliseconds()),
                                )
                            throw IllegalStateException("commit failed")
                        }
                    },
                )
            val meterRegistry = SimpleMeterRegistry()
            val processor =
                newProcessor(
                    accountService,
                    transactionManagerWithBootstrapRace,
                    meterRegistry = meterRegistry,
                    accountRepository = accountRepository,
                )
            val savedBatches = CopyOnWriteArrayList<List<Transaction>>()
            val firstAttemptStarted = CompletableDeferred<Unit>()
            val releaseFirstAttempt = CompletableDeferred<Unit>()
            val attempts = AtomicInteger()
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                savedBatches += secondArg<List<Transaction>>()
                if (attempts.incrementAndGet() == 1) {
                    firstAttemptStarted.complete(Unit)
                    releaseFirstAttempt.await()
                }
                emptyList()
            }

            try {
                // When
                processor.process(ElectionConsensusReached(firstHead, firstTransaction, emptyList()))
                firstAttemptStarted.await()
                processor.process(ElectionConsensusReached(laterHead, laterTransaction, emptyList()))
                releaseFirstAttempt.complete(Unit)

                // Then
                awaitCondition {
                    commitAttempts.get() == 2 &&
                        transactionManagerWithBootstrapRace.rollbacks == 1 &&
                        processor.getBufferSize() == 0 &&
                        meterRegistry.get("elections.processor.batch").timer().count() == 2L
                }
                assertEquals(
                    listOf(listOf(firstTransaction), listOf(laterTransaction)),
                    savedBatches.toList(),
                )
                assertEquals(
                    listOf(listOf(firstPublicKey)),
                    queriedPublicKeys.toList(),
                )
                assertPhaseCounts(meterRegistry, 1)
            } finally {
                releaseFirstAttempt.complete(Unit)
                processor.stop()
            }
        }

    @Test
    fun `partitions successful persistence through transaction cleanup`() =
        runTest {
            // Given
            val metricClock = MockClock()
            val meterRegistry = SimpleMeterRegistry(SimpleConfig.DEFAULT, metricClock)
            val transactionManager =
                RecordingReactiveTransactionManager(
                    onBegin = { metricClock.add(Duration.ofMillis(3)) },
                    onCommit = { metricClock.add(Duration.ofMillis(11)) },
                    onCleanup = { metricClock.add(Duration.ofMillis(19)) },
                )
            val accountService = mockk<AccountService>()
            val queriedPublicKeys = CopyOnWriteArrayList<List<AttoPublicKey>>()
            val accountRepository = mockk<AccountRepository>()
            every { accountRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                queriedPublicKeys += firstArg<Iterable<AttoPublicKey>>().toList()
                emptyFlow()
            }
            val processor =
                newProcessor(
                    accountService,
                    transactionManager,
                    meterRegistry,
                    accountRepository,
                )
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                metricClock.add(Duration.ofMillis(7))
                TransactionSynchronizationManager.forCurrentTransaction().awaitSingle().registerSynchronization(
                    object : TransactionSynchronization, Ordered {
                        override fun getOrder(): Int = 0

                        override fun beforeCommit(readOnly: Boolean): Mono<Void> =
                            Mono.fromRunnable { metricClock.add(Duration.ofMillis(2)) }

                        override fun afterCommit(): Mono<Void> = Mono.fromRunnable { metricClock.add(Duration.ofMillis(13)) }

                        override fun afterCompletion(status: Int): Mono<Void> = Mono.fromRunnable { metricClock.add(Duration.ofMillis(17)) }
                    },
                )
                emptyList()
            }
            val startedAt = metricClock.monotonicTime()

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), Transaction.sample(), emptyList()))

                // Then
                awaitCondition {
                    meterRegistry.get("elections.processor.batch").timer().count() == 1L &&
                        processor.getBufferSize() == 0
                }
                val expectedMilliseconds =
                    mapOf(
                        "acquire_begin" to 3.0,
                        "body" to 7.0,
                        "commit" to 13.0,
                        "after_commit" to 13.0,
                        "cleanup" to 36.0,
                    )
                expectedMilliseconds.forEach { (phase, expected) ->
                    val timer = meterRegistry.get("elections.persistence.phase").tag("phase", phase).timer()
                    assertEquals(1, timer.count())
                    assertEquals(expected, timer.totalTime(TimeUnit.MILLISECONDS), phase)
                }
                val totalNanoseconds =
                    meterRegistry.get("elections.persistence.phase").timers().sumOf { it.totalTime(TimeUnit.NANOSECONDS) }
                assertEquals((metricClock.monotonicTime() - startedAt).toDouble(), totalNanoseconds)
                assertEquals(1, meterRegistry.get("elections.processor.batch").timer().count())
                assertEquals(0, processor.getBufferSize())
                assertEquals(emptyList<List<AttoPublicKey>>(), queriedPublicKeys.toList())
            } finally {
                processor.stop()
            }
        }

    @Test
    fun `retries queued work when recovery lookup fails`() =
        runTest {
            // Given
            val commitAttempts = AtomicInteger()
            val commitFailure = IllegalStateException("commit failed")
            val transactionManager =
                RecordingReactiveTransactionManager(
                    onCommit = {
                        if (commitAttempts.incrementAndGet() == 1) {
                            throw commitFailure
                        }
                    },
                )
            val accountService = mockk<AccountService>()
            val meterRegistry = SimpleMeterRegistry()
            val transaction = Transaction.sample()
            val lookupAttempts = AtomicInteger()
            val recoveryFailure = IllegalStateException("account head lookup failed")
            val queriedPublicKeys = CopyOnWriteArrayList<List<AttoPublicKey>>()
            val accountRepository = mockk<AccountRepository>()
            every { accountRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                queriedPublicKeys += firstArg<Iterable<AttoPublicKey>>().toList()
                if (lookupAttempts.incrementAndGet() == 1) throw recoveryFailure
                emptyFlow()
            }
            val processor =
                newProcessor(
                    accountService,
                    transactionManager,
                    meterRegistry,
                    accountRepository,
                )
            val batches = CopyOnWriteArrayList<List<Transaction>>()
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                batches += secondArg<List<Transaction>>()
                emptyList()
            }

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), transaction, emptyList()))

                // Then
                awaitCondition {
                    commitAttempts.get() == 2 &&
                        transactionManager.rollbacks == 1 &&
                        processor.getBufferSize() == 0 &&
                        meterRegistry.get("elections.processor.batch").timer().count() == 1L
                }
                assertEquals(0, processor.getBufferSize())
                assertEquals(1, transactionManager.rollbacks)
                assertPhaseCounts(meterRegistry, 1)
                assertEquals(1, meterRegistry.get("elections.processor.batch").timer().count())
                assertEquals(listOf(listOf(transaction.publicKey)), queriedPublicKeys.toList())
                assertEquals(listOf(listOf(transaction), listOf(transaction)), batches.toList())
            } finally {
                processor.stop()
            }
        }

    @Test
    fun `persists sequential consensus events in FIFO order`() =
        runTest {
            // Given
            val transactionManager = RecordingReactiveTransactionManager()
            val accountService = mockk<AccountService>()
            val firstTransaction = Transaction.sample()
            val secondTransaction = Transaction.sample()
            val queriedPublicKeys = CopyOnWriteArrayList<List<AttoPublicKey>>()
            val accountRepository = mockk<AccountRepository>()
            every { accountRepository.findAllById(any<Iterable<AttoPublicKey>>()) } answers {
                queriedPublicKeys += firstArg<Iterable<AttoPublicKey>>().toList()
                emptyFlow()
            }
            val processor = newProcessor(accountService, transactionManager, accountRepository = accountRepository)
            val savedBatches = CopyOnWriteArrayList<List<Transaction>>()
            coEvery { accountService.add(TransactionSource.ELECTION, any()) } coAnswers {
                savedBatches += secondArg<List<Transaction>>()
                emptyList()
            }

            try {
                // When
                processor.process(ElectionConsensusReached(mockk(relaxed = true), firstTransaction, emptyList()))
                processor.process(ElectionConsensusReached(mockk(relaxed = true), secondTransaction, emptyList()))

                // Then
                awaitCondition { processor.getBufferSize() == 0 && savedBatches.sumOf { it.size } == 2 }
                assertEquals(
                    listOf(firstTransaction.hash, secondTransaction.hash),
                    savedBatches.flatten().map { it.hash },
                )
                assertEquals(emptyList<List<AttoPublicKey>>(), queriedPublicKeys.toList())
            } finally {
                processor.stop()
            }
        }

    private fun newProcessor(
        accountService: AccountService,
        transactionManager: ReactiveTransactionManager,
        meterRegistry: SimpleMeterRegistry = SimpleMeterRegistry(),
        accountRepository: AccountRepository =
            mockk<AccountRepository>().also { repository ->
                every { repository.findAllById(any<Iterable<AttoPublicKey>>()) } returns emptyFlow()
            },
    ): ElectionProcessor =
        ElectionProcessor(
            messagePublisher = mockk<NetworkMessagePublisher>(relaxed = true),
            accountService = accountService,
            accountRepository = accountRepository,
            meterRegistry = meterRegistry,
            transactionManager = transactionManager,
        ).also { it.start() }

    private fun awaitCondition(condition: () -> Boolean) {
        await()
            .atMost(5, TimeUnit.SECONDS)
            .pollInterval(1, TimeUnit.MILLISECONDS)
            .until(condition)
    }

    private fun assertPhaseCounts(
        meterRegistry: SimpleMeterRegistry,
        expected: Long,
    ) {
        val timers = meterRegistry.get("elections.persistence.phase").timers()
        assertEquals(5, timers.size)
        timers.forEach { assertEquals(expected, it.count(), it.id.getTag("phase")) }
    }

    private fun sampleAccount(
        publicKey: AttoPublicKey,
        lastTransactionHash: AttoHash,
        height: Long,
        lastTransactionTimestamp: Instant = Instant.EPOCH,
    ): Account =
        Account(
            publicKey = publicKey,
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            height = height,
            balance = AttoAmount(0u),
            lastTransactionTimestamp = lastTransactionTimestamp,
            lastTransactionHash = lastTransactionHash,
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = publicKey,
            persistedAt = Instant.EPOCH,
        )

    private class RecordingReactiveTransactionManager(
        private val onBegin: () -> Unit = {},
        private val onCommit: () -> Unit = {},
        private val onCleanup: () -> Unit = {},
    ) : AbstractReactiveTransactionManager() {
        private val commitCount = AtomicInteger()
        private val rollbackCount = AtomicInteger()

        val commits: Int
            get() = commitCount.get()

        val rollbacks: Int
            get() = rollbackCount.get()

        override fun doGetTransaction(synchronizationManager: TransactionSynchronizationManager): Any =
            synchronizationManager.getResource(this) ?: TestTransaction()

        override fun isExistingTransaction(transaction: Any): Boolean = (transaction as TestTransaction).active

        override fun doBegin(
            synchronizationManager: TransactionSynchronizationManager,
            transaction: Any,
            definition: TransactionDefinition,
        ): Mono<Void> =
            Mono.fromRunnable {
                onBegin()
                (transaction as TestTransaction).active = true
                synchronizationManager.bindResource(this, transaction)
            }

        override fun doCommit(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> =
            Mono.fromRunnable {
                commitCount.incrementAndGet()
                onCommit()
            }

        override fun doRollback(
            synchronizationManager: TransactionSynchronizationManager,
            status: GenericReactiveTransaction,
        ): Mono<Void> = Mono.fromRunnable { rollbackCount.incrementAndGet() }

        override fun doCleanupAfterCompletion(
            synchronizationManager: TransactionSynchronizationManager,
            transaction: Any,
        ): Mono<Void> =
            Mono.fromRunnable {
                onCleanup()
                (transaction as TestTransaction).active = false
                synchronizationManager.unbindResource(this)
            }

        private class TestTransaction(
            @Volatile var active: Boolean = false,
        )
    }

    private fun Transaction.Companion.sample(
        publicKey: AttoPublicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        height: AttoHeight = AttoHeight(2UL),
        previous: AttoHash = AttoHash(Random.nextBytes(ByteArray(32))),
        timestamp: AttoInstant = AttoInstant.now(),
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
                    timestamp = timestamp,
                    previous = previous,
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(Random.nextBytes(ByteArray(32))),
                ),
            signature = AttoSignature(Random.nextBytes(ByteArray(64))),
            work = AttoWork(Random.nextBytes(ByteArray(8))),
        )
}
