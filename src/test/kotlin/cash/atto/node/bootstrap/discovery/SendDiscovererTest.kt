package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.bootstrap.TransactionStuck
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.protocol.AttoTransactionResponse
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI

class SendDiscovererTest {
    @Test
    fun `response already in flight is admitted while discovery is paused`() =
        runTest {
            // given
            val networkMessagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val discoveryQueue = mockk<DiscoveryQueue>()
            val uncheckedTransactionRepository = mockk<UncheckedTransactionRepository>()
            val discoverer =
                SendDiscoverer(
                    networkMessagePublisher,
                    discoveryQueue,
                    uncheckedTransactionRepository,
                    SimpleMeterRegistry(),
                )
            val missingSend = sendTransaction(1)
            val receive = transaction(2, missingSend.hash)
            val response =
                InboundNetworkMessage(
                    source = MessageSource.WEBSOCKET,
                    publicUri = URI("ws://127.0.0.1:8080"),
                    socketAddress = InetSocketAddress("127.0.0.1", 8080),
                    payload = AttoTransactionResponse(missingSend),
                )
            var paused = false
            every { discoveryQueue.isAtCapacity() } answers { paused }
            coEvery { discoveryQueue.queue(any(), DiscoverySource.SEND) } returns true
            coEvery {
                uncheckedTransactionRepository.hasHigherTransaction(
                    missingSend.block.publicKey,
                    missingSend.block.height,
                )
            } returns 0L
            discoverer.process(
                TransactionStuck(
                    TransactionRejectionReason.RECEIVABLE_NOT_FOUND,
                    receive,
                ),
            )

            // when
            paused = true
            discoverer.process(response)
            discoverer.process(response)

            // then
            coVerify(exactly = 1) {
                discoveryQueue.queue(
                    match { it.transaction.hash == missingSend.hash },
                    DiscoverySource.SEND,
                )
            }
        }

    @Test
    fun `paused discovery does not suppress a send request after recovery`() {
        // given
        val networkMessagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
        val discoveryQueue = mockk<DiscoveryQueue>()
        val uncheckedTransactionRepository = mockk<UncheckedTransactionRepository>()
        val discoverer =
            SendDiscoverer(
                networkMessagePublisher,
                discoveryQueue,
                uncheckedTransactionRepository,
                SimpleMeterRegistry(),
            )
        val missingSend = sendTransaction(1)
        val receive = transaction(2, missingSend.hash)
        val stuck =
            TransactionStuck(
                TransactionRejectionReason.RECEIVABLE_NOT_FOUND,
                receive,
            )
        var paused = true
        every { discoveryQueue.isAtCapacity() } answers { paused }

        // when
        discoverer.process(stuck)
        paused = false
        discoverer.process(stuck)

        // then
        verify(exactly = 1) {
            networkMessagePublisher.publish(any())
        }
    }

    @Test
    fun `send response is left for gap discovery when the account has a higher unchecked transaction`() =
        runTest {
            // given
            val networkMessagePublisher = mockk<NetworkMessagePublisher>(relaxed = true)
            val discoveryQueue = mockk<DiscoveryQueue>(relaxed = true)
            val uncheckedTransactionRepository = mockk<UncheckedTransactionRepository>()
            val meterRegistry = SimpleMeterRegistry()
            val discoverer =
                SendDiscoverer(
                    networkMessagePublisher,
                    discoveryQueue,
                    uncheckedTransactionRepository,
                    meterRegistry,
                )
            val missingSend = sendTransaction(1)
            val firstReceive = transaction(2, missingSend.hash)
            val secondReceive = transaction(3, missingSend.hash)
            val response =
                InboundNetworkMessage(
                    source = MessageSource.WEBSOCKET,
                    publicUri = URI("ws://127.0.0.1:8080"),
                    socketAddress = InetSocketAddress("127.0.0.1", 8080),
                    payload = AttoTransactionResponse(missingSend),
                )
            coEvery {
                uncheckedTransactionRepository.hasHigherTransaction(
                    missingSend.block.publicKey,
                    missingSend.block.height,
                )
            } returns 1L
            discoverer.process(
                TransactionStuck(
                    TransactionRejectionReason.RECEIVABLE_NOT_FOUND,
                    firstReceive,
                ),
            )

            // when
            discoverer.process(response)
            discoverer.process(
                TransactionStuck(
                    TransactionRejectionReason.RECEIVABLE_NOT_FOUND,
                    secondReceive,
                ),
            )

            // then
            coVerify(exactly = 1) {
                uncheckedTransactionRepository.hasHigherTransaction(
                    missingSend.block.publicKey,
                    missingSend.block.height,
                )
            }
            coVerify(exactly = 0) {
                discoveryQueue.queue(any(), DiscoverySource.SEND)
            }
            verify(exactly = 1) {
                networkMessagePublisher.publish(any())
            }
            assertEquals(
                1.0,
                meterRegistry
                    .get("transactions.discovery.send.skipped")
                    .counter()
                    .count(),
            )
        }

    private fun sendTransaction(marker: Byte): AttoTransaction {
        val publicKey = AttoPublicKey(ByteArray(32) { marker })
        val block =
            AttoSendBlock(
                version = 0U.toAttoVersion(),
                network = AttoNetwork.LOCAL,
                algorithm = AttoAlgorithm.V1,
                publicKey = publicKey,
                height = 2U.toAttoHeight(),
                balance = AttoAmount.MAX,
                timestamp = AttoInstant.now(),
                previous = AttoHash(ByteArray(32) { (marker + 1).toByte() }),
                receiverAlgorithm = AttoAlgorithm.V1,
                receiverPublicKey = AttoPublicKey(ByteArray(32) { (marker + 2).toByte() }),
                amount = AttoAmount.MAX,
            )
        return AttoTransaction(
            block,
            AttoSignature(ByteArray(64) { (marker + 3).toByte() }),
            AttoWork(ByteArray(8) { (marker + 4).toByte() }),
        )
    }

    private fun transaction(
        marker: Byte,
        sendHash: AttoHash,
    ): Transaction =
        Transaction(
            block =
                AttoReceiveBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(ByteArray(32) { marker }),
                    height = 2U.toAttoHeight(),
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = AttoHash(ByteArray(32) { (marker + 1).toByte() }),
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = sendHash,
                ),
            signature = AttoSignature(ByteArray(64) { (marker + 2).toByte() }),
            work = AttoWork(ByteArray(8) { (marker + 3).toByte() }),
        )
}
