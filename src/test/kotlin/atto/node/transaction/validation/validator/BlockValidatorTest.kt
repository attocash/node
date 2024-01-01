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
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

internal class BlockValidatorTest {
    val privateKey = AttoPrivateKey.generate()

    val account = Account(
        publicKey = privateKey.toPublicKey(),
        version = 0u,
        algorithm = AttoAlgorithm.V1,
        height = 2u,
        balance = AttoAmount(100u),
        lastTransactionHash = AttoHash(ByteArray(32)),
        lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT.toJavaInstant(),
        representative = AttoPublicKey(ByteArray(32))
    )
    val block = AttoSendBlock(
        version = account.version,
        algorithm = AttoAlgorithm.V1,
        publicKey = privateKey.toPublicKey(),
        height = account.height + 1U,
        balance = AttoAmount(0u),
        timestamp = account.lastTransactionTimestamp.plusSeconds(1).toKotlinInstant(),
        previous = account.lastTransactionHash,
        receiverPublicKeyAlgorithm = AttoAlgorithm.V1,
        receiverPublicKey = AttoPublicKey(ByteArray(32)),
        amount = AttoAmount(100u),
    )

    val node = atto.protocol.AttoNode(
        network = AttoNetwork.LOCAL,
        protocolVersion = 0u,
        algorithm = AttoAlgorithm.V1,
        publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330),
        features = setOf(atto.protocol.NodeFeature.VOTING, atto.protocol.NodeFeature.HISTORICAL)
    )

    val transaction = Transaction(
        block,
        privateKey.sign(block.hash),
        AttoWork.work(node.network, block.timestamp, block.previous)
    )

    private val validator = BlockValidator(node);

    @Test
    fun `should validate`() = runBlocking {
        // when
        val violation = validator.validate(account, transaction)

        // then
        assertNull(violation)
    }

    @Test
    fun `should return PREVIOUS_NOT_FOUND when account height is not immediately before`() = runBlocking {
        // when
        val violation = validator.validate(account.copy(height = account.height - 1U), transaction)

        // then
        assertEquals(TransactionRejectionReason.PREVIOUS_NOT_FOUND, violation?.reason)
    }

    @Test
    fun `should return OLD_TRANSACTION when account height is after transaction height`() = runBlocking {
        // when
        val violation = validator.validate(account.copy(height = account.height + 1U), transaction)

        // then
        assertEquals(TransactionRejectionReason.OLD_TRANSACTION, violation?.reason)
    }

    @Test
    fun `should return INVALID_VERSION when account height is after transaction height`() = runBlocking {
        // when
        val violation = validator.validate(account.copy(version = (account.version + 1U).toUShort()), transaction)

        // then
        assertEquals(TransactionRejectionReason.INVALID_VERSION, violation?.reason)
    }

    @Test
    fun `should return INVALID_TIMESTAMP when account timestamp is after transaction timestamp`() = runBlocking {
        // when
        val violation = validator.validate(
            account.copy(
                lastTransactionTimestamp = account.lastTransactionTimestamp.plusSeconds(60)
            ), transaction
        )

        // then
        assertEquals(TransactionRejectionReason.INVALID_TIMESTAMP, violation?.reason)
    }

    @Test
    fun `should return INVALID_TRANSACTION when transaction is invalid`() = runBlocking {
        // when
        val violation = validator.validate(account, transaction.copy(work = AttoWork(ByteArray(8))))

        // then
        assertEquals(TransactionRejectionReason.INVALID_TRANSACTION, violation?.reason)
    }
}