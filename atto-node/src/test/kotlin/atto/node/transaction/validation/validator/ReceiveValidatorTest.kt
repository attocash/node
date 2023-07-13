package atto.node.transaction.validation.validator

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import atto.commons.*
import atto.node.account.Account
import atto.node.receivable.Receivable
import atto.node.receivable.ReceivableRepository
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

internal class ReceiveValidatorTest {
    val privateKey = AttoPrivateKey.generate()

    val account = Account(
        publicKey = privateKey.toPublicKey(),
        version = 0u,
        height = 2u,
        balance = AttoAmount(0u),
        lastTransactionHash = AttoHash(ByteArray(32)),
        lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT,
        representative = AttoPublicKey(ByteArray(32))
    )
    val block = AttoReceiveBlock(
        version = account.version,
        publicKey = privateKey.toPublicKey(),
        height = account.height + 1U,
        balance = AttoAmount(10U),
        timestamp = account.lastTransactionTimestamp.plusSeconds(1),
        previous = account.lastTransactionHash,
        sendHash = AttoHash(ByteArray(32))
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

    @Test
    fun `should validate`() = runBlocking {
        // given
        val receivable = Receivable(
            hash = block.sendHash,
            receiverPublicKey = block.publicKey,
            amount = block.balance - account.balance
        )

        val receivableRepository = mockk<ReceivableRepository>()
        coEvery { receivableRepository.findById(block.sendHash) } returns receivable

        val validator = ReceiveValidator(receivableRepository)

        // when
        val violation = validator.validate(account, transaction)

        // then
        assertNull(violation)
    }


    @Test
    fun `should return SEND_NOT_FOUND when receivable is not found`() = runBlocking {
        // given
        val receivableRepository = mockk<ReceivableRepository>()
        coEvery { receivableRepository.findById(block.sendHash) } returns null

        val validator = ReceiveValidator(receivableRepository)

        // when
        val violation = validator.validate(account, transaction)

        // then
        assertEquals(TransactionRejectionReason.RECEIVABLE_NOT_FOUND, violation?.reason)
    }


    @Test
    fun `should return INVALID_RECEIVER when previous is different`() = runBlocking {
        // given
        val byteArray = ByteArray(32)
        byteArray.fill(1)
        val receivable = Receivable(
            hash = block.sendHash,
            receiverPublicKey = AttoPublicKey(byteArray),
            amount = block.balance - account.balance
        )

        val receivableRepository = mockk<ReceivableRepository>()
        coEvery { receivableRepository.findById(block.sendHash) } returns receivable

        val validator = ReceiveValidator(receivableRepository)

        // when
        val violation = validator.validate(account, transaction)

        // then
        assertEquals(TransactionRejectionReason.INVALID_RECEIVER, violation?.reason)
    }


    @Test
    fun `should return INVALID_BALANCE when previous is different`() = runBlocking {
        // given
        val receivable = Receivable(
            hash = block.sendHash,
            receiverPublicKey = account.publicKey,
            amount = AttoAmount(2UL)
        )

        val receivableRepository = mockk<ReceivableRepository>()
        coEvery { receivableRepository.findById(block.sendHash) } returns receivable

        val validator = ReceiveValidator(receivableRepository)

        // when
        val violation = validator.validate(account, transaction)

        // then
        assertEquals(TransactionRejectionReason.INVALID_BALANCE, violation?.reason)
    }
}