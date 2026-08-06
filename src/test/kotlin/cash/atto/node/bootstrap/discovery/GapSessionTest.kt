package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.bootstrap.TransactionDiscovered
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.protocol.AttoTransactionStreamResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class GapSessionTest {
    @Test
    fun `in order descending responses are queued and complete the session`() =
        runTest {
            // Given
            val chain = chain()
            val admitted = mutableListOf<AttoHash>()
            val session = session(chain, queue(admitted))

            // When
            session.offer(response(chain.third))
            session.offer(response(chain.second))
            val status = session.offer(response(chain.first))

            // Then
            assertEquals(GapSessionStatus.COMPLETED, status)
            assertEquals(
                listOf(chain.third.hash, chain.second.hash, chain.first.hash),
                admitted,
            )
            assertEquals(0, session.bufferedResponseCount())
        }

    @Test
    fun `out of order responses are retained and drained in descending order`() =
        runTest {
            // Given
            val chain = chain()
            val admitted = mutableListOf<AttoHash>()
            val session = session(chain, queue(admitted))

            // When
            session.offer(response(chain.first))
            session.offer(response(chain.second))
            val status = session.offer(response(chain.third))

            // Then
            assertEquals(GapSessionStatus.COMPLETED, status)
            assertEquals(
                listOf(chain.third.hash, chain.second.hash, chain.first.hash),
                admitted,
            )
        }

    @Test
    fun `saturated queue serializes a later response without losing it`() =
        runTest {
            // Given
            val chain = chain()
            val enteredQueue = CompletableDeferred<Unit>()
            val releaseQueue = CompletableDeferred<Unit>()
            val admitted = mutableListOf<AttoHash>()
            val queue = mockk<DiscoveryQueue>()
            coEvery { queue.queue(any(), DiscoverySource.GAP) } coAnswers {
                val hash = firstArg<TransactionDiscovered>().transaction.hash
                if (hash == chain.third.hash) {
                    enteredQueue.complete(Unit)
                    releaseQueue.await()
                }
                admitted += hash
                true
            }
            val session = session(chain, queue)
            val expectedOffer = async { session.offer(response(chain.third)) }
            enteredQueue.await()

            // When
            val laterOffer = async { session.offer(response(chain.first)) }
            runCurrent()

            // Then
            assertFalse(laterOffer.isCompleted)
            assertEquals(2, session.bufferedResponseCount())

            // When
            releaseQueue.complete(Unit)
            assertEquals(GapSessionStatus.ACTIVE, expectedOffer.await())
            assertEquals(GapSessionStatus.ACTIVE, laterOffer.await())
            val status = session.offer(response(chain.second))

            // Then
            assertEquals(GapSessionStatus.COMPLETED, status)
            assertEquals(
                listOf(chain.third.hash, chain.second.hash, chain.first.hash),
                admitted,
            )
        }

    @Test
    fun `cancelled admission is resumed by the waiting response`() =
        runTest {
            // Given
            val chain = chain()
            val enteredQueue = CompletableDeferred<Unit>()
            val attempts = AtomicInteger()
            val admitted = mutableListOf<AttoHash>()
            val queue = mockk<DiscoveryQueue>()
            coEvery { queue.queue(any(), DiscoverySource.GAP) } coAnswers {
                if (attempts.getAndIncrement() == 0) {
                    enteredQueue.complete(Unit)
                    awaitCancellation()
                }
                admitted += firstArg<TransactionDiscovered>().transaction.hash
                true
            }
            val session = session(chain, queue)
            val expectedOffer = async { session.offer(response(chain.third)) }
            enteredQueue.await()
            val laterOffer = async { session.offer(response(chain.first)) }
            runCurrent()

            // When
            expectedOffer.cancelAndJoin()
            assertEquals(GapSessionStatus.ACTIVE, laterOffer.await())

            // Then
            assertEquals(1, session.bufferedResponseCount())
            assertEquals(GapSessionStatus.ACTIVE, session.status())

            // When
            val status = session.offer(response(chain.second))

            // Then
            assertEquals(GapSessionStatus.COMPLETED, status)
            assertEquals(
                listOf(chain.third.hash, chain.second.hash, chain.first.hash),
                admitted,
            )
        }

    @Test
    fun `cancelled final response remains buffered for explicit retry`() =
        runTest {
            // Given
            val chain = chain()
            val enteredQueue = CompletableDeferred<Unit>()
            val attempts = AtomicInteger()
            val admitted = mutableListOf<AttoHash>()
            val queue = mockk<DiscoveryQueue>()
            coEvery { queue.queue(any(), DiscoverySource.GAP) } coAnswers {
                if (attempts.getAndIncrement() == 0) {
                    enteredQueue.complete(Unit)
                    awaitCancellation()
                }
                admitted += firstArg<TransactionDiscovered>().transaction.hash
                true
            }
            val session =
                GapSession(
                    publicKey = chain.publicKey,
                    peer = PEER,
                    startHeight = 3U.toAttoHeight(),
                    endHeight = 3U.toAttoHeight(),
                    initialExpectedHash = chain.third.hash,
                    discoveryQueue = queue,
                    clock = Clock.systemUTC(),
                )
            val offer = async { session.offer(response(chain.third)) }
            enteredQueue.await()

            // When
            offer.cancelAndJoin()

            // Then
            assertEquals(1, session.bufferedResponseCount())
            assertEquals(GapSessionStatus.ACTIVE, session.status())

            // When
            val status = session.retryOne()

            // Then
            assertEquals(GapSessionStatus.COMPLETED, status)
            assertEquals(listOf(chain.third.hash), admitted)
            assertEquals(0, session.bufferedResponseCount())
        }

    @Test
    fun `wrong peer account range and processed duplicates are ignored`() =
        runTest {
            // Given
            val chain = chain()
            val admitted = mutableListOf<AttoHash>()
            val session = session(chain, queue(admitted))
            val wrongAccount = transaction(publicKey(9), 3U, chain.second.hash, 9)
            val outsideRange = transaction(chain.publicKey, 4U, chain.third.hash, 4)

            // When
            session.offer(response(chain.third, WRONG_PEER))
            session.offer(response(wrongAccount))
            session.offer(response(outsideRange))
            session.offer(response(chain.third))
            session.offer(response(chain.third))
            session.offer(response(chain.second))
            val status = session.offer(response(chain.first))

            // Then
            assertEquals(GapSessionStatus.COMPLETED, status)
            assertEquals(
                listOf(chain.third.hash, chain.second.hash, chain.first.hash),
                admitted,
            )
        }

    @Test
    fun `wrong hash at expected height invalidates the session`() =
        runTest {
            // Given
            val chain = chain()
            val queue = mockk<DiscoveryQueue>()
            val session =
                GapSession(
                    publicKey = chain.publicKey,
                    peer = PEER,
                    startHeight = 1U.toAttoHeight(),
                    endHeight = 3U.toAttoHeight(),
                    initialExpectedHash = hash(99),
                    discoveryQueue = queue,
                    clock = Clock.systemUTC(),
                )

            // When
            val status = session.offer(response(chain.third))

            // Then
            assertEquals(GapSessionStatus.INVALID, status)
            assertEquals(0, session.bufferedResponseCount())
            coVerify(exactly = 0) { queue.queue(any(), any()) }
        }

    @Test
    fun `irrelevant traffic does not extend the timeout`() =
        runTest {
            // Given
            val chain = chain()
            val clock = MutableClock()
            val session = session(chain, mockk(relaxed = true), clock)
            clock.advance(Duration.ofSeconds(59))

            // When
            session.offer(response(chain.third, WRONG_PEER))
            clock.advance(Duration.ofSeconds(2))

            // Then
            assertTrue(session.isExpired(clock.instant(), Duration.ofMinutes(1)))
        }

    @Test
    fun `active queue admission suppresses timeout expiry`() =
        runTest {
            // Given
            val chain = chain()
            val clock = MutableClock()
            val enteredQueue = CompletableDeferred<Unit>()
            val releaseQueue = CompletableDeferred<Unit>()
            val queue = mockk<DiscoveryQueue>()
            coEvery { queue.queue(any(), DiscoverySource.GAP) } coAnswers {
                enteredQueue.complete(Unit)
                releaseQueue.await()
                true
            }
            val session = session(chain, queue, clock)
            val offer = async { session.offer(response(chain.third)) }
            enteredQueue.await()

            // When
            clock.advance(Duration.ofMinutes(2))

            // Then
            assertFalse(session.isExpired(clock.instant(), Duration.ofMinutes(1)))

            // When
            releaseQueue.complete(Unit)
            offer.await()

            // Then
            assertFalse(session.isExpired(clock.instant(), Duration.ofMinutes(1)))
        }

    @Test
    fun `response map cannot exceed the requested height range`() =
        runTest {
            // Given
            val publicKey = publicKey(1)
            val transactions =
                (1U..5U).associateWith { height ->
                    transaction(
                        publicKey,
                        height,
                        hash((height - 1U).toByte()),
                        height.toByte(),
                    )
                }
            val enteredQueue = CompletableDeferred<Unit>()
            val queue = mockk<DiscoveryQueue>()
            coEvery { queue.queue(any(), DiscoverySource.GAP) } coAnswers {
                enteredQueue.complete(Unit)
                awaitCancellation()
            }
            val session =
                GapSession(
                    publicKey = publicKey,
                    peer = PEER,
                    startHeight = 1U.toAttoHeight(),
                    endHeight = 5U.toAttoHeight(),
                    initialExpectedHash = transactions.getValue(5U).hash,
                    discoveryQueue = queue,
                    clock = Clock.systemUTC(),
                )
            val expectedOffer = async { session.offer(response(transactions.getValue(5U))) }
            enteredQueue.await()

            // When
            val pendingOffers = mutableListOf(expectedOffer)
            (1U..4U).forEach { height ->
                pendingOffers += async { session.offer(response(transactions.getValue(height))) }
                pendingOffers += async { session.offer(response(transactions.getValue(height))) }
            }
            pendingOffers +=
                async {
                    session.offer(response(transaction(publicKey, 6U, hash(5), 6)))
                }
            runCurrent()

            // Then
            assertEquals(5, session.bufferedResponseCount())

            pendingOffers.forEach { it.cancelAndJoin() }
        }

    private fun session(
        chain: Chain,
        queue: DiscoveryQueue,
        clock: Clock = Clock.systemUTC(),
    ): GapSession =
        GapSession(
            publicKey = chain.publicKey,
            peer = PEER,
            startHeight = 1U.toAttoHeight(),
            endHeight = 3U.toAttoHeight(),
            initialExpectedHash = chain.third.hash,
            discoveryQueue = queue,
            clock = clock,
        )

    private fun queue(admitted: MutableList<AttoHash>): DiscoveryQueue =
        mockk<DiscoveryQueue>().also { queue ->
            coEvery { queue.queue(any(), DiscoverySource.GAP) } coAnswers {
                admitted += firstArg<TransactionDiscovered>().transaction.hash
                true
            }
        }

    private fun chain(): Chain {
        val publicKey = publicKey(1)
        val first = transaction(publicKey, 1U, hash(0), 1)
        val second = transaction(publicKey, 2U, first.hash, 2)
        val third = transaction(publicKey, 3U, second.hash, 3)
        return Chain(publicKey, first, second, third)
    }

    private fun response(
        transaction: AttoTransaction,
        peer: URI = PEER,
    ): InboundNetworkMessage<AttoTransactionStreamResponse> =
        InboundNetworkMessage(
            source = MessageSource.WEBSOCKET,
            publicUri = peer,
            socketAddress = InetSocketAddress("127.0.0.1", 8080),
            payload = AttoTransactionStreamResponse(transaction),
        )

    private data class Chain(
        val publicKey: AttoPublicKey,
        val first: AttoTransaction,
        val second: AttoTransaction,
        val third: AttoTransaction,
    )

    private class MutableClock(
        private var now: Instant = Instant.parse("2026-08-04T00:00:00Z"),
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = now

        fun advance(duration: Duration) {
            now = now.plus(duration)
        }
    }

    private companion object {
        val PEER: URI = URI("ws://127.0.0.1:8080")
        val WRONG_PEER: URI = URI("ws://127.0.0.1:8081")

        fun transaction(
            publicKey: AttoPublicKey,
            height: UInt,
            previous: AttoHash,
            marker: Byte,
        ): AttoTransaction {
            val block =
                AttoSendBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = publicKey,
                    height = height.toAttoHeight(),
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = previous,
                    receiverAlgorithm = AttoAlgorithm.V1,
                    receiverPublicKey = publicKey((marker + 20).toByte()),
                    amount = AttoAmount.MAX,
                )
            return AttoTransaction(
                block = block,
                signature = AttoSignature(ByteArray(64) { (marker + 1).toByte() }),
                work = AttoWork(ByteArray(8) { (marker + 2).toByte() }),
            )
        }

        fun publicKey(marker: Byte): AttoPublicKey = AttoPublicKey(ByteArray(32) { marker })

        fun hash(marker: Byte): AttoHash = AttoHash(ByteArray(32) { marker })
    }
}
