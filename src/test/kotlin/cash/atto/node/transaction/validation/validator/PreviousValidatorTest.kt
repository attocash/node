package cash.atto.node.transaction.validation.validator

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoChangeBlock
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.sign
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toPublicKey
import cash.atto.commons.worker.AttoWorker
import cash.atto.commons.worker.cpu
import cash.atto.node.account.Account
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.random.Random

internal class PreviousValidatorTest {
    val privateKey = AttoPrivateKey.generate()

    val account =
        Account(
            network = AttoNetwork.LOCAL,
            publicKey = privateKey.toPublicKey(),
            algorithm = AttoAlgorithm.V1,
            version = 0U.toAttoVersion(),
            height = 2U.toAttoHeight(),
            balance = AttoAmount(0u),
            lastTransactionHash = AttoHash(ByteArray(32)),
            lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT.toJavaInstant(),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = AttoPublicKey(ByteArray(32)),
        )
    val block =
        AttoChangeBlock(
            network = AttoNetwork.LOCAL,
            version = account.version,
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            height = account.height + 1U,
            balance = AttoAmount(0U),
            timestamp = account.lastTransactionTimestamp.plusSeconds(1).toKotlinInstant(),
            previous = account.lastTransactionHash,
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = privateKey.toPublicKey(),
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

    private val validator = PreviousValidator()

    @Test
    fun `should validate`() =
        runBlocking {
            // when
            val violation = validator.validate(account, transaction)

            // then
            assertNull(violation)
        }

    @Test
    fun `should return INVALID_PREVIOUS when previous is different`() =
        runBlocking {
            // when
            val byteArray = ByteArray(32)
            byteArray.fill(1)
            val violation = validator.validate(account.copy(lastTransactionHash = AttoHash(byteArray)), transaction)

            // then
            assertEquals(TransactionRejectionReason.INVALID_PREVIOUS, violation?.reason)
        }
}
