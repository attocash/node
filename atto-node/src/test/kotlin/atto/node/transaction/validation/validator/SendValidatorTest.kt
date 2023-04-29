package atto.node.transaction.validation.validator

import kotlinx.coroutines.runBlocking
import atto.commons.*
import atto.node.account.Account
import atto.node.transaction.Transaction
import atto.node.transaction.TransactionRejectionReason
import atto.protocol.AttoNode
import atto.protocol.NodeFeature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

internal class SendValidatorTest {
    val privateKey = AttoPrivateKey.generate()

    val account = Account(
        publicKey = privateKey.toPublicKey(),
        version = 0u,
        height = 2u,
        balance = AttoAmount(100u),
        lastTransactionHash = AttoHash(ByteArray(32)),
        lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT,
        representative = AttoPublicKey(ByteArray(32))
    )
    val block = AttoSendBlock(
        version = account.version,
        publicKey = privateKey.toPublicKey(),
        height = account.height + 1U,
        balance = AttoAmount(0u),
        timestamp = account.lastTransactionTimestamp.plusSeconds(1),
        previous = account.lastTransactionHash,
        receiverPublicKey = privateKey.toPublicKey(),
        amount = AttoAmount(100u),
    )

    val node = atto.protocol.AttoNode(
        network = AttoNetwork.LOCAL,
        protocolVersion = 0u,
        publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330),
        features = setOf(atto.protocol.NodeFeature.VOTING, atto.protocol.NodeFeature.HISTORICAL)
    )

    val transaction = Transaction(
        block,
        privateKey.sign(block.hash),
        AttoWork.work(node.network, block.timestamp, block.previous)
    )

    private val validator = SendValidator()

    @Test
    fun `should validate`() = runBlocking {
        // when
        val violation = validator.validate(account, transaction)

        // then
        assertNull(violation)
    }

    @Test
    fun `should return INVALID_AMOUNT when account height is not immediately before`() = runBlocking {
        // when
        val violation = validator.validate(account.copy(balance = account.balance + AttoAmount(1UL)), transaction)

        // then
        assertEquals(TransactionRejectionReason.INVALID_AMOUNT, violation?.reason)
    }
}