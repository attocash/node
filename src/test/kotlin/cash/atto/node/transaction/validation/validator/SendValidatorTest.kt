package cash.atto.node.transaction.validation.validator

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.sign
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.commons.toPublicKey
import cash.atto.commons.worker.AttoWorker
import cash.atto.commons.worker.cpu
import cash.atto.node.account.Account
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.random.Random

internal class SendValidatorTest {
    val privateKey = AttoPrivateKey.generate()

    val account =
        Account(
            publicKey = privateKey.toPublicKey(),
            network = AttoNetwork.LOCAL,
            algorithm = AttoAlgorithm.V1,
            version = 0U.toAttoVersion(),
            height = 2,
            balance = AttoAmount(100u),
            lastTransactionHash = AttoHash(ByteArray(32)),
            lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT.toJavaInstant(),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = AttoPublicKey(ByteArray(32)),
        )
    val block =
        AttoSendBlock(
            network = AttoNetwork.LOCAL,
            version = account.version,
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            height = AttoHeight((account.height + 1).toULong()),
            balance = AttoAmount(0u),
            timestamp = account.lastTransactionTimestamp.plusSeconds(1).toAtto(),
            previous = account.lastTransactionHash,
            receiverAlgorithm = AttoAlgorithm.V1,
            receiverPublicKey = privateKey.toPublicKey(),
            amount = AttoAmount(100u),
        )

    val node =
        AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            publicUri = URI("ws://localhost:8081"),
            features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL),
        )

    val transaction =
        Transaction(
            block,
            runBlocking { privateKey.sign(block.hash) },
            runBlocking { AttoWorker.cpu().work(block) },
        )

    private val validator = SendValidator()

    @Test
    fun `should validate`() =
        runBlocking {
            // when
            val violation = validator.validate(account, transaction)

            // then
            assertNull(violation)
        }

    @Test
    fun `should return INVALID_AMOUNT when account height is not immediately before`() =
        runBlocking {
            // when
            val violation = validator.validate(account.copy(balance = account.balance + AttoAmount(1UL)), transaction)

            // then
            assertEquals(TransactionRejectionReason.INVALID_AMOUNT, violation?.reason)
        }
}
