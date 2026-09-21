package cash.atto.node.bootstrap.discovery

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.node.EventPublisher
import cash.atto.node.account.Account
import cash.atto.node.account.AccountRepository
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import cash.atto.node.network.BroadcastNetworkMessage
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.network.NodeConnectionManager
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRepository
import cash.atto.node.transaction.toTransaction
import cash.atto.node.vote.convertion.VoteConverter
import cash.atto.node.vote.weight.VoteWeighter
import cash.atto.protocol.AttoBootstrapTransactionPush
import cash.atto.protocol.AttoNode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.firstArg
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant

class LastDiscovererBroadcastTest {
    @Test
    fun `configured account head is included once alongside random sample`() =
        runTest {
            val random = transaction(1)
            val hinted = transaction(2)
            val hintedAddress = AttoAddress(hinted.algorithm, hinted.publicKey)
            val properties = DiscoveryProperties().apply { hintedAddresses = setOf(hintedAddress) }

            val accountRepository = mockk<AccountRepository>()
            coEvery { accountRepository.findById(hinted.publicKey) } returns account(hinted)

            val transactionRepository = mockk<TransactionRepository>()
            coEvery { transactionRepository.getLastSample(10L) } returns flowOf(random, hinted)
            coEvery { transactionRepository.findById(hinted.hash) } returns hinted

            val uncheckedRepository = mockk<UncheckedTransactionRepository>()
            coEvery { uncheckedRepository.count() } returns 0

            val sent = mutableListOf<BroadcastNetworkMessage<*>>()
            val connectionManager = mockk<NodeConnectionManager>()
            coEvery { connectionManager.send(any<BroadcastNetworkMessage<*>>()) } answers {
                sent += firstArg<BroadcastNetworkMessage<*>>()
            }

            val thisNode = mockk<AttoNode>()
            every { thisNode.isNotHistorical() } returns false

            val discoverer =
                LastDiscoverer(
                    thisNode = thisNode,
                    discoveryProperties = properties,
                    accountRepository = accountRepository,
                    transactionRepository = transactionRepository,
                    uncheckedTransactionRepository = uncheckedRepository,
                    nodeConnectionManager = connectionManager,
                    networkMessagePublisher = mockk<NetworkMessagePublisher>(relaxed = true),
                    eventPublisher = mockk<EventPublisher>(relaxed = true),
                    discoveryQueue = mockk<DiscoveryQueue>(relaxed = true),
                    voteConverter = mockk<VoteConverter>(relaxed = true),
                    voteWeighter = mockk<VoteWeighter>(relaxed = true),
                )

            try {
                discoverer.broadcastSample()

                val hashes =
                    sent.map {
                        (it.payload as AttoBootstrapTransactionPush).transaction.hash
                    }
                assertEquals(listOf(random.hash, hinted.hash), hashes)
            } finally {
                discoverer.close()
            }
        }

    private fun account(transaction: Transaction): Account =
        Account(
            publicKey = transaction.publicKey,
            network = transaction.block.network,
            version = 0U.toAttoVersion(),
            algorithm = transaction.algorithm,
            height = transaction.height.value.toLong(),
            balance = transaction.block.balance,
            lastTransactionTimestamp = Instant.now(),
            lastTransactionHash = transaction.hash,
            representativeAlgorithm = transaction.algorithm,
            representativePublicKey = transaction.publicKey,
        )

    private fun transaction(marker: Byte): Transaction =
        AttoTransaction(
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
                    sendHash = AttoHash(ByteArray(32) { (marker + 2).toByte() }),
                ),
            signature = AttoSignature(ByteArray(64) { (marker + 3).toByte() }),
            work = AttoWork(ByteArray(8) { (marker + 4).toByte() }),
        ).toTransaction()
}
