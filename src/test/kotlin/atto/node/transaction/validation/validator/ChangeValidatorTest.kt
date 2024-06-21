package atto.node.transaction.validation.validator

import atto.node.account.Account
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionRejectionReason
import cash.atto.commons.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.random.Random

internal class ChangeValidatorTest {
    val privateKey = AttoPrivateKey.generate()

    val account =
        Account(
            publicKey = privateKey.toPublicKey(),
            version = 0u,
            algorithm = AttoAlgorithm.V1,
            height = 2u,
            balance = AttoAmount(0u),
            lastTransactionHash = AttoHash(ByteArray(32)),
            lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT.toJavaInstant(),
            representative = AttoPublicKey(ByteArray(32)),
        )
    val block =
        AttoChangeBlock(
            version = account.version,
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            height = account.height + 1U,
            balance = AttoAmount(0U),
            timestamp = account.lastTransactionTimestamp.plusSeconds(1).toKotlinInstant(),
            previous = account.lastTransactionHash,
            representative = privateKey.toPublicKey(),
        )

    val node =
        atto.protocol.AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            algorithm = AttoAlgorithm.V1,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            publicUri = URI("ws://localhost:8081"),
            features = setOf(atto.protocol.NodeFeature.VOTING, atto.protocol.NodeFeature.HISTORICAL),
        )

    val transaction =
        Transaction(
            block,
            privateKey.sign(block.hash),
            AttoWork.work(node.network, block.timestamp, block.previous),
        )

    private val validator = ChangeValidator()

    @Test
    fun `should validate`() =
        runBlocking {
            // when
            val violation = validator.validate(account, transaction)

            // then
            assertNull(violation)
        }

    @Test
    fun `should return INVALID_REPRESENTATIVE when representative is equals previous one`() =
        runBlocking {
            // when
            val violation = validator.validate(account.copy(representative = block.representative), transaction)

            // then
            assertEquals(TransactionRejectionReason.INVALID_REPRESENTATIVE, violation?.reason)
        }

    @Test
    fun `should return INVALID_BALANCE when balance is different from previous balance`() =
        runBlocking {
            // when
            val violation = validator.validate(account.copy(balance = AttoAmount(1UL)), transaction)

            // then
            assertEquals(TransactionRejectionReason.INVALID_BALANCE, violation?.reason)
        }
}
