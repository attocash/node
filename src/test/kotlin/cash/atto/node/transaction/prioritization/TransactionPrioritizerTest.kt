package cash.atto.node.transaction.prioritization

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.PreviousSupport
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.commons.toPublicKeyBlocking
import cash.atto.commons.worker.AttoWorker
import cash.atto.node.ApplicationConfiguration
import cash.atto.node.Event
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.Election
import cash.atto.node.election.ElectionConsensusReached
import cash.atto.node.election.ElectionDiscarded
import cash.atto.node.election.ElectionExpired
import cash.atto.node.election.ElectionLost
import cash.atto.node.election.ElectionProperties
import cash.atto.node.election.ElectionRejected
import cash.atto.node.election.ElectionStarted
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionDropped
import cash.atto.node.transaction.TransactionFailure
import cash.atto.node.transaction.TransactionReceived
import cash.atto.node.transaction.TransactionRejected
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.node.transaction.TransactionSource
import cash.atto.node.transaction.TransactionValidated
import cash.atto.node.transaction.TransactionValidationFailed
import cash.atto.node.vote.Vote
import cash.atto.node.vote.VoteValidated
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoTransactionPush
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationListener
import org.springframework.context.PayloadApplicationEvent
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.event.SimpleApplicationEventMulticaster
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

internal class TransactionPrioritizerTest {
    @Test
    fun `delayed election start cannot strand the next height through asynchronous event delivery`() {
        // Given
        val chain = Chain()
        val parent = chain.receive(chain.initial)
        val successor = chain.receive(chain.saved(parent))
        val unrelated = Chain().let { it.receive(it.initial) }
        val received = CopyOnWriteArrayList<Transaction>()
        val errors = CopyOnWriteArrayList<Throwable>()
        val delayedStartObserved = CountDownLatch(1)
        val registry = SimpleMeterRegistry()
        AnnotationConfigApplicationContext().use { context ->
            val publisher = EventPublisher(context)
            val prioritizer = prioritizer(1, publisher, registry)
            val multicaster = ApplicationConfiguration().applicationEventMulticaster() as SimpleApplicationEventMulticaster
            multicaster.setErrorHandler { errors.add(it) }
            context.beanFactory.registerSingleton("applicationEventMulticaster", multicaster)
            context.beanFactory.registerSingleton("transactionPrioritizer", prioritizer)
            context.addApplicationListener(
                ApplicationListener<ApplicationEvent> { event ->
                    when (val payload = (event as? PayloadApplicationEvent<*>)?.payload) {
                        is TransactionReceived -> received += payload.transaction
                        is ElectionStarted -> delayedStartObserved.countDown()
                    }
                },
            )
            context.refresh()
            try {
                prioritizer.add(parent)
                awaitCondition { received.size == 1 }

                // When
                publisher.publish(chain.updated(parent))
                awaitCondition { pendingCount(registry) == 0.0 }
                publisher.publish(ElectionStarted(chain.initial, parent))
                assertTrue(delayedStartObserved.await(5, TimeUnit.SECONDS))
                awaitStable { pendingCount(registry) == 0.0 }
                prioritizer.add(successor)
                awaitCondition { received.size == 2 }
                prioritizer.add(unrelated)
                publisher.publish(TransactionValidationFailed(successor, IllegalStateException("Validation unavailable")))

                // Then
                awaitCondition { received.size == 3 }
                assertEquals(listOf(parent.hash, successor.hash, unrelated.hash), received.map { it.hash })
                assertEquals(0, prioritizer.getBufferSize())
                assertTrue(errors.isEmpty(), errors.toString())
            } finally {
                prioritizer.stop()
            }
        }
    }

    @Test
    fun `queued children recheck parent dependencies and duplicate hashes never overlap processing`() {
        // Given
        val chain = Chain()
        val parent = chain.receive(chain.initial)
        val child = chain.receive(chain.saved(parent), receivedLagSeconds = 10)
        val blocker = Chain().let { it.receive(it.initial) }
        val publishingBlocker = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        Fixture(maxActive = 10, onReceived = {
            if (it.hash == blocker.hash) {
                publishingBlocker.countDown()
                check(releaseBlocker.await(5, TimeUnit.SECONDS)) { "Timed out releasing blocked publication" }
            }
        }).use { fixture ->
            try {
                fixture.prioritizer.add(blocker)
                assertTrue(publishingBlocker.await(5, TimeUnit.SECONDS))
                fixture.prioritizer.add(parent)
                fixture.prioritizer.add(child)
                fixture.prioritizer.add(child.copy(receivedAt = child.receivedAt.plusSeconds(1)))
                assertEquals(2, fixture.prioritizer.getQueueSize())

                // When
                releaseBlocker.countDown()
                fixture.awaitReceived(2)
                awaitCondition { fixture.prioritizer.getBufferSize() == 1 }
                fixture.prioritizer.add(parent.copy(receivedAt = parent.receivedAt.plusSeconds(1)))

                // Then
                awaitStable { fixture.received.size == 2 && fixture.prioritizer.getQueueSize() == 0 }
                assertEquals(listOf(blocker.hash, parent.hash), fixture.received.map { it.hash })
                assertEquals(1, fixture.prioritizer.getBufferSize())

                // When
                fixture.prioritizer.process(chain.updated(parent))

                // Then
                fixture.awaitReceived(3)
                awaitStable { fixture.received.size == 3 }
                assertEquals(listOf(blocker.hash, parent.hash, child.hash), fixture.received.map { it.hash })
                assertEquals(0, fixture.prioritizer.getBufferSize())
            } finally {
                releaseBlocker.countDown()
            }
        }
    }

    @Test
    fun `buffered transaction hashes are deduplicated across receipt time and work variants`() {
        // Given
        val chain = Chain()
        val parent = chain.receive(chain.initial)
        val child = chain.receive(chain.saved(parent))
        val otherWork =
            AttoWork(
                child.work.value
                    .copyOf()
                    .apply { this[0] = (this[0].toInt() xor 1).toByte() },
            )
        Fixture(maxActive = 2).use { fixture ->
            fixture.prioritizer.add(parent)
            fixture.awaitReceived(1)

            // When
            fixture.prioritizer.add(child)
            fixture.prioritizer.add(child.copy(receivedAt = child.receivedAt.plusSeconds(1)))
            fixture.prioritizer.add(child.copy(work = otherWork, receivedAt = child.receivedAt.plusSeconds(2)))

            // Then
            assertEquals(1, fixture.prioritizer.getBufferSize())
            assertEquals(0, fixture.prioritizer.getQueueSize())

            // When
            fixture.prioritizer.process(chain.updated(parent))

            // Then
            fixture.awaitReceived(2)
            awaitStable { fixture.received.size == 2 }
            assertEquals(listOf(parent.hash, child.hash), fixture.received.map { it.hash })
            assertEquals(0, fixture.prioritizer.getBufferSize())
            assertEquals(1.0, pendingCount(fixture.registry))
        }
    }

    @Test
    fun `concurrent parent completion and child buffering never lose a child`() {
        // Given
        val chain = Chain()
        val parent = chain.receive(chain.initial)
        val children = List(32) { chain.receive(chain.saved(parent)) }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            Fixture(maxActive = children.size, queueGroupSize = children.size).use { fixture ->
                fixture.prioritizer.add(parent)
                fixture.awaitReceived(1)
                fixture.prioritizer.add(children.first())
                assertEquals(1, fixture.prioritizer.getBufferSize())

                // When
                val buffering =
                    executor.submit {
                        check(start.await(5, TimeUnit.SECONDS)) { "Timed out starting child buffering" }
                        children.drop(1).forEach(fixture.prioritizer::add)
                    }
                val completion =
                    executor.submit {
                        check(start.await(5, TimeUnit.SECONDS)) { "Timed out starting parent completion" }
                        fixture.prioritizer.process(chain.updated(parent))
                    }
                start.countDown()
                buffering.get(5, TimeUnit.SECONDS)
                completion.get(5, TimeUnit.SECONDS)

                // Then
                fixture.awaitReceived(children.size + 1)
                assertEquals((children + parent).map { it.hash }.toSet(), fixture.received.map { it.hash }.toSet())
                assertEquals(0, fixture.prioritizer.getBufferSize())
                assertEquals(0, fixture.prioritizer.getQueueSize())
            }
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @ParameterizedTest
    @EnumSource(FailureKind::class)
    fun `processing failures release buffered children and occupied validation capacity`(failureKind: FailureKind) {
        // Given
        val chain = Chain()
        val parent = chain.receive(chain.initial)
        val child = chain.receive(chain.saved(parent))
        val unrelated = Chain().let { it.receive(it.initial) }
        Fixture(maxActive = 1).use { fixture ->
            fixture.prioritizer.add(parent)
            fixture.awaitReceived(1)
            fixture.prioritizer.add(child)
            fixture.prioritizer.add(unrelated)
            awaitStable {
                fixture.received.size == 1 && fixture.prioritizer.getBufferSize() == 1 && fixture.prioritizer.getQueueSize() == 1
            }

            // When
            fixture.prioritizer.process(failureKind.event(chain.initial, parent))
            fixture.awaitReceived(2)
            val second = fixture.received.last()
            fixture.prioritizer.process(TransactionValidationFailed(second, IllegalStateException("Validation unavailable")))

            // Then
            fixture.awaitReceived(3)
            assertEquals(setOf(parent.hash, child.hash, unrelated.hash), fixture.received.map { it.hash }.toSet())
            assertEquals(0, fixture.prioritizer.getBufferSize())
            assertEquals(0, fixture.prioritizer.getQueueSize())
        }
    }

    @Test
    fun `queue eviction failure releases the hash for a later direct admission`() {
        // Given
        val blockerChain = Chain()
        val blocker = blockerChain.receive(blockerChain.initial)
        val dropped = Chain().let { it.receive(it.initial, receivedLagSeconds = 10) }
        val retained = Chain().let { it.receive(it.initial) }
        Fixture(maxActive = 1, queueGroupSize = 1).use { fixture ->
            fixture.prioritizer.add(blocker)
            fixture.awaitReceived(1)
            fixture.prioritizer.add(dropped)
            fixture.prioritizer.add(retained)
            val failure = fixture.events.filterIsInstance<TransactionDropped>().single()
            assertEquals(dropped.hash, failure.transaction.hash)

            // When
            fixture.prioritizer.process(failure)
            fixture.prioritizer.process(blockerChain.updated(blocker))
            fixture.awaitReceived(2)
            fixture.prioritizer.add(dropped)
            assertEquals(1, fixture.prioritizer.getQueueSize())
            fixture.prioritizer.process(TransactionValidationFailed(retained, IllegalStateException("Validation unavailable")))

            // Then
            fixture.awaitReceived(3)
            assertEquals(listOf(blocker.hash, retained.hash, dropped.hash), fixture.received.map { it.hash })
            assertEquals(0, fixture.prioritizer.getQueueSize())
        }
    }

    @Test
    fun `delayed queue drop cannot release a newer active copy of the same hash or its capacity`() {
        // Given
        val blockerChain = Chain()
        val blocker = blockerChain.receive(blockerChain.initial)
        val chain = Chain()
        val oldQueued = chain.receive(chain.initial, receivedLagSeconds = 10)
        val retry = oldQueued.copy(receivedAt = oldQueued.receivedAt.plusSeconds(1))
        val retained = Chain().let { it.receive(it.initial) }
        val unrelated = Chain().let { it.receive(it.initial) }
        Fixture(maxActive = 1, queueGroupSize = 1).use { fixture ->
            fixture.prioritizer.add(blocker)
            fixture.awaitReceived(1)
            fixture.prioritizer.add(oldQueued)
            fixture.prioritizer.add(retained)
            val delayedDrop = fixture.events.filterIsInstance<TransactionDropped>().single()
            assertEquals(oldQueued.hash, delayedDrop.transaction.hash)
            fixture.prioritizer.process(blockerChain.updated(blocker))
            fixture.awaitReceived(2)
            fixture.prioritizer.add(retry)
            fixture.prioritizer.process(TransactionValidationFailed(retained, IllegalStateException("Validation unavailable")))
            fixture.awaitReceived(3)
            assertEquals(retry.receivedAt, fixture.received.last().receivedAt)
            fixture.prioritizer.add(unrelated)

            // When
            fixture.prioritizer.process(delayedDrop)

            // Then
            awaitStable {
                fixture.received.size == 3 && fixture.prioritizer.getQueueSize() == 1 && pendingCount(fixture.registry) == 1.0
            }

            // When
            fixture.prioritizer.process(chain.updated(retry))

            // Then
            fixture.awaitReceived(4)
            assertEquals(listOf(blocker.hash, retained.hash, retry.hash, unrelated.hash), fixture.received.map { it.hash })
            assertEquals(0, fixture.prioritizer.getQueueSize())
        }
    }

    @Test
    fun `recipient receive waits for a pending send before validation dispatch`() {
        // Given
        val sender = Chain()
        val recipient = Chain()
        val send = sender.send(sender.initial, recipient.initial.publicKey)
        val recipientAccount = recipient.initial.copy(lastTransactionTimestamp = send.block.timestamp.toJavaInstant())
        val receive = recipient.receive(recipientAccount, sendHash = send.hash)
        Fixture(maxActive = 2).use { fixture ->
            fixture.prioritizer.add(send)
            fixture.awaitReceived(1)
            fixture.prioritizer.add(receive)
            awaitStable { fixture.received.size == 1 && fixture.prioritizer.getBufferSize() == 1 }

            // When
            fixture.prioritizer.process(sender.updated(send))

            // Then
            fixture.awaitReceived(2)
            assertEquals(listOf(send.hash, receive.hash), fixture.received.map { it.hash })
            assertEquals(0, fixture.prioritizer.getBufferSize())
        }
    }

    @Test
    fun `rejected candidate releases capacity while the winner awaits persistence`() =
        runBlocking {
            // Given
            val chain = Chain()
            val winner = chain.receive(chain.initial)
            val rejectedCandidate = chain.receive(chain.initial)
            val next = chain.receive(chain.saved(winner))
            val unrelated = Chain().let { it.receive(it.initial) }
            Fixture(maxActive = 2).use { fixture ->
                val weights = mockk<VoteWeighter>()
                every { weights.getMinimalConfirmationWeight() } returns AttoAmount(1_000UL)
                every { weights.get(any()) } returns AttoAmount(1_000UL)
                val election = Election(ElectionProperties(), weights, fixture.publisher, SimpleMeterRegistry())
                fixture.prioritizer.add(winner)
                fixture.awaitReceived(1)
                election.start(TransactionValidated(chain.initial, winner))
                election.process(VoteValidated(winner, vote(winner)))
                assertEquals(1, fixture.events.filterIsInstance<ElectionConsensusReached>().size)
                fixture.prioritizer.add(rejectedCandidate)
                fixture.awaitReceived(2)
                election.start(TransactionValidated(chain.initial, rejectedCandidate))
                val rejection = fixture.events.filterIsInstance<ElectionRejected>().single()
                assertEquals(rejectedCandidate.hash, rejection.transaction.hash)
                assertEquals(listOf(winner.hash), fixture.events.filterIsInstance<ElectionStarted>().map { it.transaction.hash })
                fixture.prioritizer.add(next)
                fixture.prioritizer.add(unrelated)
                assertEquals(1, fixture.prioritizer.getBufferSize())
                assertEquals(1, fixture.prioritizer.getQueueSize())

                // When
                fixture.prioritizer.process(rejection)

                // Then
                fixture.awaitReceived(3)
                assertEquals(unrelated.hash, fixture.received.last().hash)
                assertEquals(1, fixture.prioritizer.getBufferSize())

                // When
                fixture.prioritizer.process(chain.updated(winner))

                // Then
                fixture.awaitReceived(4)
                assertEquals(
                    setOf(winner.hash, rejectedCandidate.hash, next.hash, unrelated.hash),
                    fixture.received.map { it.hash }.toSet(),
                )
                assertEquals(0, fixture.prioritizer.getBufferSize())
                assertEquals(0, fixture.prioritizer.getQueueSize())
            }
        }

    @Test
    fun `duplicate older account updates preserve active next height and its buffered successor`() {
        // Given
        val chain = Chain()
        val parent = chain.receive(chain.initial)
        val next = chain.receive(chain.saved(parent))
        val successor = chain.receive(chain.saved(next))
        Fixture(maxActive = 1).use { fixture ->
            fixture.prioritizer.add(parent)
            fixture.awaitReceived(1)
            fixture.prioritizer.process(chain.updated(parent))
            fixture.prioritizer.add(next)
            fixture.awaitReceived(2)
            fixture.prioritizer.add(successor)
            assertEquals(1, fixture.prioritizer.getBufferSize())

            // When
            fixture.prioritizer.process(chain.updated(parent))
            fixture.prioritizer.process(chain.updated(parent))

            // Then
            awaitStable {
                fixture.received.size == 2 && fixture.prioritizer.getBufferSize() == 1 && pendingCount(fixture.registry) == 1.0
            }

            // When
            fixture.prioritizer.process(chain.updated(next))

            // Then
            fixture.awaitReceived(3)
            assertEquals(listOf(parent.hash, next.hash, successor.hash), fixture.received.map { it.hash })
            assertEquals(0, fixture.prioritizer.getBufferSize())
        }
    }

    @ParameterizedTest
    @EnumSource(value = FailureKind::class, names = ["EXPIRED", "REJECTED", "VALIDATION_FAILED"])
    fun `recoverable processing failure allows an inbound transaction hash to be retried`(failureKind: FailureKind) {
        // Given
        val chain = Chain()
        val transaction = chain.receive(chain.initial)
        val message =
            InboundNetworkMessage(
                MessageSource.WEBSOCKET,
                URI("ws://127.0.0.1:8082"),
                InetSocketAddress("127.0.0.1", 8082),
                AttoTransactionPush(transaction.toAttoTransaction()),
            )
        Fixture(maxActive = 1).use { fixture ->
            fixture.prioritizer.add(message)
            fixture.awaitReceived(1)
            fixture.prioritizer.add(message)
            awaitStable { fixture.received.size == 1 }

            // When
            fixture.prioritizer.process(failureKind.event(chain.initial, transaction))
            fixture.prioritizer.add(message)

            // Then
            fixture.awaitReceived(2)
            assertEquals(listOf(transaction.hash, transaction.hash), fixture.received.map { it.hash })
        }
    }

    @Test
    fun `inbound duplicate check racing recoverable failure retains the replacement attempt`() {
        // Given
        val chain = Chain()
        val transaction = chain.receive(chain.initial)
        val message =
            InboundNetworkMessage(
                MessageSource.WEBSOCKET,
                URI("ws://127.0.0.1:8082"),
                InetSocketAddress("127.0.0.1", 8082),
                AttoTransactionPush(transaction.toAttoTransaction()),
            )
        val duplicateCheckEntered = CountDownLatch(1)
        val releaseDuplicateCheck = CountDownLatch(1)
        val duplicateRemoved = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val delayFirstCheck = AtomicBoolean(true)
        Fixture(maxActive = 1).use { fixture ->
            // Gate the real Caffeine operations without replacing cache behavior or exposing production test hooks.
            val detectorField = TransactionPrioritizer::class.java.getDeclaredField("duplicateDetector").apply { isAccessible = true }
            val detector = detectorField.get(fixture.prioritizer)
            val cacheField = detector.javaClass.getDeclaredField("cache").apply { isAccessible = true }

            @Suppress("UNCHECKED_CAST")
            val cache = cacheField.get(detector) as ConcurrentMap<AttoHash, AttoHash>
            cacheField.set(
                detector,
                object : ConcurrentMap<AttoHash, AttoHash> by cache {
                    override fun putIfAbsent(
                        key: AttoHash,
                        value: AttoHash,
                    ): AttoHash? {
                        if (delayFirstCheck.compareAndSet(true, false)) {
                            duplicateCheckEntered.countDown()
                            check(releaseDuplicateCheck.await(5, TimeUnit.SECONDS)) { "Timed out releasing inbound duplicate check" }
                        }
                        return cache.putIfAbsent(key, value)
                    }

                    override fun remove(key: AttoHash): AttoHash? {
                        val previous = cache.remove(key)
                        duplicateRemoved.countDown()
                        check(releaseFailure.await(5, TimeUnit.SECONDS)) { "Timed out releasing recoverable failure" }
                        return previous
                    }
                },
            )
            val executor = Executors.newFixedThreadPool(2)
            try {
                val delayedInbound = executor.submit { fixture.prioritizer.add(message) }
                assertTrue(duplicateCheckEntered.await(5, TimeUnit.SECONDS))
                fixture.prioritizer.add(message)
                fixture.awaitReceived(1)

                // When
                val failure =
                    executor.submit {
                        fixture.prioritizer.process(
                            TransactionValidationFailed(transaction, IllegalStateException("Validation unavailable")),
                        )
                    }
                assertTrue(duplicateRemoved.await(5, TimeUnit.SECONDS))
                releaseDuplicateCheck.countDown()
                delayedInbound.get(5, TimeUnit.SECONDS)

                // Then
                fixture.awaitReceived(2)
                releaseFailure.countDown()
                failure.get(5, TimeUnit.SECONDS)
                awaitStable { pendingCount(fixture.registry) == 1.0 && fixture.prioritizer.getQueueSize() == 0 }
                assertEquals(listOf(transaction.hash, transaction.hash), fixture.received.map { it.hash })
            } finally {
                releaseDuplicateCheck.countDown()
                releaseFailure.countDown()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            }
        }
    }

    @Test
    fun `recoverable failure contract allows inbound retry without a concrete failure type`() {
        // Given
        val chain = Chain()
        val failedTransaction = chain.receive(chain.initial)
        val message =
            InboundNetworkMessage(
                MessageSource.WEBSOCKET,
                URI("ws://127.0.0.1:8082"),
                InetSocketAddress("127.0.0.1", 8082),
                AttoTransactionPush(failedTransaction.toAttoTransaction()),
            )
        val failure =
            object : TransactionFailure {
                override val transaction = failedTransaction
                override val recoverable = true
                override val timestamp = Instant.now()
            }
        Fixture(maxActive = 1).use { fixture ->
            fixture.prioritizer.add(message)
            fixture.awaitReceived(1)
            fixture.prioritizer.add(message)
            awaitStable { fixture.received.size == 1 }

            // When
            fixture.prioritizer.process(failure)
            fixture.prioritizer.add(message)

            // Then
            fixture.awaitReceived(2)
            assertEquals(listOf(failedTransaction.hash, failedTransaction.hash), fixture.received.map { it.hash })
        }
    }

    @Test
    fun `nonrecoverable rejection preserves inbound hash suppression while releasing capacity`() {
        // Given
        val chain = Chain()
        val transaction = chain.receive(chain.initial)
        val unrelated = Chain().let { it.receive(it.initial) }
        val currentAccount = chain.initial.copy(lastTransactionHash = AttoHash(Random.nextBytes(32)))
        val message =
            InboundNetworkMessage(
                MessageSource.WEBSOCKET,
                URI("ws://127.0.0.1:8082"),
                InetSocketAddress("127.0.0.1", 8082),
                AttoTransactionPush(transaction.toAttoTransaction()),
            )
        Fixture(maxActive = 1).use { fixture ->
            fixture.prioritizer.add(message)
            fixture.awaitReceived(1)
            fixture.prioritizer.add(unrelated)
            assertEquals(1, fixture.prioritizer.getQueueSize())

            // When
            fixture.prioritizer.process(
                TransactionRejected(
                    TransactionRejectionReason.INVALID_PREVIOUS,
                    "Previous hash does not match",
                    currentAccount,
                    transaction,
                ),
            )
            fixture.prioritizer.add(message)

            // Then
            fixture.awaitReceived(2)
            awaitStable { fixture.received.size == 2 && fixture.prioritizer.getQueueSize() == 0 }
            assertEquals(listOf(transaction.hash, unrelated.hash), fixture.received.map { it.hash })
            assertEquals(1.0, pendingCount(fixture.registry))
        }
    }

    enum class FailureKind {
        EXPIRED,
        LOST,
        ELECTION_REJECTED,
        ELECTION_DISCARDED,
        REJECTED,
        VALIDATION_FAILED,
        ;

        fun event(
            account: Account,
            transaction: Transaction,
        ): TransactionFailure =
            when (this) {
                EXPIRED -> {
                    ElectionExpired(account, transaction)
                }

                LOST -> {
                    ElectionLost(account, transaction)
                }

                ELECTION_REJECTED -> {
                    ElectionRejected(account, transaction)
                }

                ELECTION_DISCARDED -> {
                    ElectionDiscarded(
                        account.copy(
                            height = transaction.height.value.toLong(),
                            balance = transaction.block.balance,
                            lastTransactionHash = transaction.hash,
                            lastTransactionTimestamp = transaction.block.timestamp.toJavaInstant(),
                        ),
                        transaction,
                    )
                }

                REJECTED -> {
                    TransactionRejected(
                        TransactionRejectionReason.RECEIVABLE_NOT_FOUND,
                        "Receivable is missing",
                        account,
                        transaction,
                    )
                }

                VALIDATION_FAILED -> {
                    TransactionValidationFailed(transaction, IllegalStateException("Validation unavailable"))
                }
            }
    }

    private class Fixture(
        maxActive: Int,
        queueGroupSize: Int = 10,
        onReceived: (Transaction) -> Unit = {},
    ) : AutoCloseable {
        val events = CopyOnWriteArrayList<Event>()
        val received = CopyOnWriteArrayList<Transaction>()
        val registry = SimpleMeterRegistry()
        val publisher = mockk<EventPublisher>()
        val prioritizer: TransactionPrioritizer

        init {
            every { publisher.publish(any()) } answers {
                val event = firstArg<Event>()
                events += event
                if (event is TransactionReceived) {
                    received += event.transaction
                    onReceived(event.transaction)
                }
            }
            prioritizer = prioritizer(maxActive, publisher, registry, queueGroupSize)
        }

        fun awaitReceived(count: Int) {
            awaitCondition { received.size >= count }
            assertEquals(count, received.size)
        }

        override fun close() {
            prioritizer.stop()
            registry.close()
        }
    }

    private class Chain {
        private val privateKey = AttoPrivateKey.generate()
        val initial =
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

        fun receive(
            account: Account,
            sendHash: AttoHash = AttoHash(Random.nextBytes(32)),
            receivedLagSeconds: Long = 1,
        ): Transaction =
            runBlocking {
                val block =
                    AttoReceiveBlock(
                        version = account.version,
                        network = account.network,
                        algorithm = account.algorithm,
                        publicKey = account.publicKey,
                        height = AttoHeight((account.height + 1).toULong()),
                        balance = AttoAmount(account.balance.raw + 10UL),
                        timestamp = account.lastTransactionTimestamp.plusSeconds(1).toAtto(),
                        previous = account.lastTransactionHash,
                        sendHashAlgorithm = AttoAlgorithm.V1,
                        sendHash = sendHash,
                    )
                Transaction(
                    block,
                    privateKey.sign(block.hash),
                    AttoWorker.cpu().work(block),
                    receivedAt = block.timestamp.toJavaInstant().plusSeconds(receivedLagSeconds),
                )
            }

        fun send(
            account: Account,
            receiverPublicKey: AttoPublicKey,
        ): Transaction =
            runBlocking {
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
                        receiverPublicKey = receiverPublicKey,
                        amount = AttoAmount(10UL),
                    )
                Transaction(block, privateKey.sign(block.hash), AttoWorker.cpu().work(block))
            }

        fun saved(transaction: Transaction): Account =
            initial.copy(
                height = transaction.height.value.toLong(),
                balance = transaction.block.balance,
                lastTransactionHash = transaction.hash,
                lastTransactionTimestamp = transaction.block.timestamp.toJavaInstant(),
            )

        fun updated(transaction: Transaction): AccountUpdated =
            AccountUpdated(
                TransactionSource.ELECTION,
                saved(transaction).copy(
                    height = transaction.height.value.toLong() - 1,
                    balance =
                        when (val block = transaction.block) {
                            is AttoSendBlock -> block.balance + block.amount
                            else -> AttoAmount(block.balance.raw - 10UL)
                        },
                    lastTransactionHash = (transaction.block as PreviousSupport).previous,
                    lastTransactionTimestamp =
                        transaction.block.timestamp
                            .toJavaInstant()
                            .minusSeconds(1),
                ),
                saved(transaction),
                transaction,
            )
    }

    private companion object {
        fun prioritizer(
            maxActive: Int,
            publisher: EventPublisher,
            registry: SimpleMeterRegistry,
            queueGroupSize: Int = 10,
        ): TransactionPrioritizer =
            TransactionPrioritizer(
                TransactionPrioritizationProperties().apply {
                    groupMaxSize = queueGroupSize
                    maxActiveElections = maxActive
                },
                publisher,
                registry,
            ).also { it.start() }

        fun pendingCount(registry: SimpleMeterRegistry): Double =
            registry.get("transactions.prioritizer.pending.dependencies").gauge().value()

        fun vote(transaction: Transaction): Vote =
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

        fun awaitCondition(condition: () -> Boolean) {
            await()
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.MILLISECONDS)
                .until(condition)
        }

        fun awaitStable(condition: () -> Boolean) {
            await()
                .during(100, TimeUnit.MILLISECONDS)
                .atMost(5, TimeUnit.SECONDS)
                .pollInterval(1, TimeUnit.MILLISECONDS)
                .until(condition)
        }
    }
}
