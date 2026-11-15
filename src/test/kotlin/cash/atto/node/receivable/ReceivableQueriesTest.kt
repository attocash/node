package cash.atto.node.receivable

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.node.AccountSearch
import cash.atto.commons.toAttoVersion
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ReceivableQueriesTest {
    @Test
    fun `subscribes to live events before starting the database query`(): Unit =
        runTest {
            // Given
            val row = receivable(1, amount = 200UL)
            val live = receivable(2)
            val repository = mockk<ReceivableRepository>()
            val queries = ReceivableQueries(repository)
            coEvery { repository.findAllDesc(any(), any()) } coAnswers {
                queries.process(ReceivableSaved(live))
                flowOf(row)
            }
            val received = mutableListOf<AttoReceivable>()

            // When
            backgroundScope.launch { queries.stream(row.receiverPublicKey, AttoAmount(1UL)).toList(received) }
            runCurrent()

            // Then
            assertEquals(listOf(row, live).map { it.toAttoReceivable() }, received)
        }

    @Test
    fun `queues live receivables until the database scan completes`(): Unit =
        runTest {
            // Given
            val first = receivable(1, amount = 300UL)
            val second = receivable(2, amount = 200UL)
            val live = receivable(3)
            val scanPaused = CompletableDeferred<Unit>()
            val finishScan = CompletableDeferred<Unit>()
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns
                flow {
                    emit(first)
                    scanPaused.complete(Unit)
                    finishScan.await()
                    emit(second)
                }
            val queries = ReceivableQueries(repository)
            val received = mutableListOf<AttoReceivable>()
            backgroundScope.launch { queries.stream(first.receiverPublicKey, AttoAmount(1UL)).toList(received) }
            runCurrent()
            assertTrue(scanPaused.isCompleted)

            // When
            queries.process(ReceivableSaved(live))
            runCurrent()

            // Then
            assertEquals(listOf(first.toAttoReceivable()), received)

            // When
            finishScan.complete(Unit)
            runCurrent()

            // Then
            assertEquals(listOf(first, second, live).map { it.toAttoReceivable() }, received)
        }

    @Test
    fun `filters queued and delayed database notifications after handing off to live events`(): Unit =
        runTest {
            // Given
            val queuedOverlap = receivable(1, amount = 300UL)
            val delayedOverlap = receivable(2, amount = 200UL)
            val firstLive = receivable(3)
            val secondLive = receivable(4)
            val finishScan = CompletableDeferred<Unit>()
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns
                flow {
                    emit(queuedOverlap)
                    finishScan.await()
                    emit(delayedOverlap)
                }
            val queries = ReceivableQueries(repository)
            val received = mutableListOf<AttoReceivable>()
            backgroundScope.launch { queries.stream(queuedOverlap.receiverPublicKey, AttoAmount(1UL)).toList(received) }
            runCurrent()

            // When
            queries.process(ReceivableSaved(queuedOverlap))
            finishScan.complete(Unit)
            runCurrent()
            queries.process(ReceivableSaved(firstLive))
            runCurrent()

            // Then
            assertEquals(listOf(queuedOverlap, delayedOverlap, firstLive).map { it.toAttoReceivable() }, received)

            // When
            advanceTimeBy(60_000)
            queries.process(ReceivableSaved(delayedOverlap))
            queries.process(ReceivableSaved(secondLive))
            runCurrent()

            // Then
            assertEquals(listOf(queuedOverlap, delayedOverlap, firstLive, secondLive).map { it.toAttoReceivable() }, received)
        }

    @Test
    fun `preserves distinct pending receivables and database amount order across senders`(): Unit =
        runTest {
            // Given
            val largest = receivable(1, amount = 300UL).copy(timestamp = Instant.EPOCH.plusSeconds(3))
            val otherSender = receivable(2, amount = 200UL).copy(publicKey = AttoPublicKey(ByteArray(32) { 3 }))
            val olderFromSameSender = receivable(3, amount = 100UL).copy(timestamp = Instant.EPOCH.plusSeconds(1))
            val rows = listOf(largest, otherSender, olderFromSameSender)
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns flowOf(*rows.toTypedArray())
            val queries = ReceivableQueries(repository)

            // When
            val received = queries.stream(largest.receiverPublicKey, AttoAmount(1UL)).take(rows.size).toList()

            // Then
            assertEquals(rows.map { it.toAttoReceivable() }, received)
        }

    @Test
    fun `each collection of the same flow has its own database hashes`(): Unit =
        runTest {
            // Given
            val row = receivable(1)
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns flowOf(row)
            val queries = ReceivableQueries(repository)
            val stream = queries.stream(row.receiverPublicKey, AttoAmount(1UL))
            val first = mutableListOf<AttoReceivable>()
            val second = mutableListOf<AttoReceivable>()

            // When
            val firstCollection = backgroundScope.launch { stream.toList(first) }
            runCurrent()
            firstCollection.cancel()
            runCurrent()
            backgroundScope.launch { stream.toList(second) }
            runCurrent()

            // Then
            assertEquals(listOf(row.toAttoReceivable()), first)
            assertEquals(first, second)
            coVerify(exactly = 2) { repository.findAllDesc(listOf(row.receiverPublicKey), AttoAmount(1UL)) }
        }

    @Test
    fun `concurrent subscribers receive live events independently when one cancels`(): Unit =
        runTest {
            // Given
            val row = receivable(1)
            val live = receivable(2)
            val laterLive = receivable(3)
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns flowOf(row)
            val queries = ReceivableQueries(repository)
            val first = mutableListOf<AttoReceivable>()
            val second = mutableListOf<AttoReceivable>()
            val firstCollection = backgroundScope.launch { queries.stream(row.receiverPublicKey, AttoAmount(1UL)).toList(first) }
            backgroundScope.launch { queries.stream(row.receiverPublicKey, AttoAmount(1UL)).toList(second) }
            runCurrent()

            // When
            queries.process(ReceivableSaved(live))
            runCurrent()
            firstCollection.cancel()
            runCurrent()
            queries.process(ReceivableSaved(laterLive))
            runCurrent()

            // Then
            assertEquals(listOf(row, live).map { it.toAttoReceivable() }, first)
            assertEquals(listOf(row, live, laterLive).map { it.toAttoReceivable() }, second)
        }

    @Test
    fun `backpressures live publication during a slow scan and preserves every buffered event`(): Unit =
        runTest {
            // Given
            val row = receivable(1, amount = 300UL)
            val live = (2..257).map { receivable(it) }
            val finishScan = CompletableDeferred<Unit>()
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns
                flow {
                    finishScan.await()
                    emit(row)
                }
            val queries = ReceivableQueries(repository)
            val received = mutableListOf<AttoReceivable>()
            backgroundScope.launch { queries.stream(row.receiverPublicKey, AttoAmount(1UL)).toList(received) }
            runCurrent()

            // When
            val publisher = backgroundScope.launch { live.forEach { queries.process(ReceivableSaved(it)) } }
            runCurrent()

            // Then
            assertFalse(publisher.isCompleted)
            assertTrue(received.isEmpty())

            // When
            finishScan.complete(Unit)
            runCurrent()

            // Then
            assertTrue(publisher.isCompleted)
            assertEquals((listOf(row) + live).map { it.toAttoReceivable() }, received)
        }

    @Test
    fun `cancelling an active database scan cancels the query and detaches the live listener`(): Unit =
        runTest {
            // Given
            val row = receivable(1)
            val scanStarted = CompletableDeferred<Unit>()
            val scanCancelled = CompletableDeferred<Unit>()
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns
                flow {
                    try {
                        scanStarted.complete(Unit)
                        awaitCancellation()
                    } finally {
                        scanCancelled.complete(Unit)
                    }
                }
            val queries = ReceivableQueries(repository)
            val collection = backgroundScope.launch { queries.stream(row.receiverPublicKey, AttoAmount(1UL)).collect() }
            runCurrent()
            assertTrue(scanStarted.isCompleted)
            val publisher = backgroundScope.launch { (2..257).forEach { queries.process(ReceivableSaved(receivable(it))) } }
            runCurrent()
            assertFalse(publisher.isCompleted)

            // When
            collection.cancel()
            runCurrent()

            // Then
            assertTrue(scanCancelled.isCompleted)
            assertTrue(publisher.isCompleted)
        }

    @Test
    fun `a database query failure propagates and detaches the live listener`(): Unit =
        runTest {
            // Given
            val row = receivable(1)
            val failScan = CompletableDeferred<Unit>()
            val failure = IllegalStateException("Database connection failed")
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns
                flow {
                    failScan.await()
                    throw failure
                }
            val queries = ReceivableQueries(repository)
            val collection =
                backgroundScope.async {
                    runCatching { queries.stream(row.receiverPublicKey, AttoAmount(1UL)).collect() }
                }
            runCurrent()
            val publisher = backgroundScope.launch { (2..257).forEach { queries.process(ReceivableSaved(receivable(it))) } }
            runCurrent()
            assertFalse(publisher.isCompleted)

            // When
            failScan.complete(Unit)
            runCurrent()

            // Then
            assertTrue(collection.isCompleted)
            val propagated = assertInstanceOf(IllegalStateException::class.java, collection.await().exceptionOrNull())
            assertEquals(failure.message, propagated.message)
            assertTrue(publisher.isCompleted)
        }

    @Test
    fun `filters live events by requested receivers and inclusive minimum amount`(): Unit =
        runTest {
            // Given
            val first = receivable(1, amount = 100UL)
            val second = receivable(2, amount = 101UL).copy(receiverPublicKey = AttoPublicKey(ByteArray(32) { 3 }))
            val belowMinimum = receivable(3, amount = 99UL)
            val unrequested = receivable(4, amount = 100UL).copy(receiverPublicKey = AttoPublicKey(ByteArray(32) { 4 }))
            val search = AccountSearch(listOf(first, second).map { AttoAddress(it.receiverAlgorithm, it.receiverPublicKey) })
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns emptyFlow()
            val queries = ReceivableQueries(repository)
            val received = mutableListOf<AttoReceivable>()
            backgroundScope.launch { queries.stream(search, AttoAmount(100UL)).toList(received) }
            runCurrent()

            // When
            listOf(belowMinimum, unrequested, first, second).forEach { queries.process(ReceivableSaved(it)) }
            runCurrent()

            // Then
            assertEquals(listOf(first, second).map { it.toAttoReceivable() }, received)
            coVerify { repository.findAllDesc(listOf(first.receiverPublicKey, second.receiverPublicKey), AttoAmount(100UL)) }
        }

    @Test
    fun `delays future timestamps without blocking ready receivables`(): Unit =
        runTest {
            // Given
            val future = receivable(1, amount = 200UL).copy(timestamp = Instant.now().plusSeconds(60))
            val ready = receivable(2)
            val repository = mockk<ReceivableRepository>()
            coEvery { repository.findAllDesc(any(), any()) } returns flowOf(future, ready)
            val queries = ReceivableQueries(repository)
            val received = mutableListOf<AttoReceivable>()

            // When
            backgroundScope.launch { queries.stream(ready.receiverPublicKey, AttoAmount(1UL)).toList(received) }
            runCurrent()

            // Then
            assertEquals(listOf(ready.toAttoReceivable()), received)

            // When
            advanceTimeBy(61_000)
            runCurrent()

            // Then
            assertEquals(listOf(ready, future).map { it.toAttoReceivable() }, received)
        }

    private fun receivable(
        hash: Int,
        amount: ULong = 100UL,
    ): Receivable =
        Receivable(
            hash =
                AttoHash(
                    ByteArray(32).apply {
                        this[0] = hash.toByte()
                        this[1] = (hash ushr 8).toByte()
                    },
                ),
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(ByteArray(32) { 2 }),
            timestamp = Instant.EPOCH,
            receiverAlgorithm = AttoAlgorithm.V1,
            receiverPublicKey = AttoPublicKey(ByteArray(32) { 1 }),
            amount = AttoAmount(amount),
        )
}
