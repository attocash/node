package cash.atto.node.account

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
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.URI
import kotlin.random.Random

class AccountServiceTest {
    @Test
    fun `rejects multiple transactions for the same public key in one batch`() {
        val publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32)))
        val service =
            AccountService(
                thisNode = sampleNode(),
                accountRepository = mockk(),
                accountEntryService = mockk(),
                transactionService = mockk(),
                receivableRepository = mockk(),
                eventPublisher = mockk(),
            )
        val firstTransaction = Transaction.sample(publicKey = publicKey, height = AttoHeight(2UL))
        val secondTransaction = Transaction.sample(publicKey = publicKey, height = AttoHeight(3UL))

        val exception =
            assertThrows<IllegalArgumentException> {
                runBlocking {
                    service.add(TransactionSource.ELECTION, listOf(firstTransaction, secondTransaction))
                }
            }

        assertEquals(
            "Cannot add multiple transactions for the same public key in one account batch: $publicKey",
            exception.message,
        )
    }

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
        publicKey: AttoPublicKey,
        height: AttoHeight,
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
