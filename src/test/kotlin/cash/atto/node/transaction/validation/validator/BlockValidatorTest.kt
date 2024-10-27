package cash.atto.node.transaction.validation.validator

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.AttoWork
import cash.atto.commons.sign
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

internal class BlockValidatorTest {
    val privateKey = AttoPrivateKey.generate()

    val account =
        Account(
            publicKey = privateKey.toPublicKey(),
            network = AttoNetwork.LOCAL,
            version = 0U.toAttoVersion(),
            algorithm = AttoAlgorithm.V1,
            height = 2,
            balance = AttoAmount(100u),
            lastTransactionHash = AttoHash(ByteArray(32)),
            lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT.toJavaInstant(),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = AttoPublicKey(ByteArray(32)),
        )
    val block =
        AttoSendBlock(
            version = account.version,
            network = AttoNetwork.LOCAL,
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            height = AttoHeight(account.height.toULong() + 1U),
            balance = AttoAmount(0u),
            timestamp = account.lastTransactionTimestamp.plusSeconds(1).toKotlinInstant(),
            previous = account.lastTransactionHash,
            receiverAlgorithm = AttoAlgorithm.V1,
            receiverPublicKey = AttoPublicKey(ByteArray(32)),
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

    private val validator = BlockValidator(node)

    @Test
    fun `should validate`() =
        runBlocking {
            // when
            val violation = validator.validate(account, transaction)

            // then
            assertNull(violation)
        }

    @Test
    fun `should return PREVIOUS_NOT_FOUND when account height is not immediately before`() =
        runBlocking {
            // when
            val violation = validator.validate(account.copy(height = account.height - 1), transaction)

            // then
            assertEquals(TransactionRejectionReason.PREVIOUS_NOT_FOUND, violation?.reason)
        }

    @Test
    fun `should return OLD_TRANSACTION when account height is after transaction height`() =
        runBlocking {
            // when
            val violation = validator.validate(account.copy(height = account.height + 1), transaction)

            // then
            assertEquals(TransactionRejectionReason.OLD_TRANSACTION, violation?.reason)
        }

    @Test
    fun `should return INVALID_VERSION when account height is after transaction height`() =
        runBlocking {
            // when
            val violation = validator.validate(account.copy(version = account.version + 1U), transaction)

            // then
            assertEquals(TransactionRejectionReason.INVALID_VERSION, violation?.reason)
        }

    @Test
    fun `should return INVALID_TIMESTAMP when account timestamp is after transaction timestamp`() =
        runBlocking {
            // when
            val violation =
                validator.validate(
                    account.copy(
                        lastTransactionTimestamp = account.lastTransactionTimestamp.plusSeconds(60),
                    ),
                    transaction,
                )

            // then
            assertEquals(TransactionRejectionReason.INVALID_TIMESTAMP, violation?.reason)
        }

    @Test
    fun `should return INVALID_TRANSACTION when transaction is invalid`() =
        runBlocking {
            // when
            val violation = validator.validate(account, transaction.copy(work = AttoWork(ByteArray(8))))

            // then
            assertEquals(TransactionRejectionReason.INVALID_TRANSACTION, violation?.reason)
        }
}
