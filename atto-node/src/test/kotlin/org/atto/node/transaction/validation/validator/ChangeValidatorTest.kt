package org.atto.node.transaction.validation.validator

import kotlinx.coroutines.runBlocking
import org.atto.commons.*
import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.protocol.AttoNode
import org.atto.protocol.NodeFeature
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.random.Random

internal class ChangeValidatorTest {
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
    val block = AttoChangeBlock(
        version = account.version,
        publicKey = privateKey.toPublicKey(),
        height = account.height + 1U,
        balance = AttoAmount(0U),
        timestamp = account.lastTransactionTimestamp.plusSeconds(1),
        previous = account.lastTransactionHash,
        representative = privateKey.toPublicKey()
    )

    val node = AttoNode(
        network = AttoNetwork.LOCAL,
        protocolVersion = 0u,
        publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
        socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330),
        features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL)
    )

    val transaction = Transaction(
        block,
        privateKey.sign(block.hash),
        AttoWorks.work(node.network, block.timestamp, block.previous)
    )

    private val validator = ChangeValidator();

    @Test
    fun `should validate`() = runBlocking {
        // when
        val violation = validator.validate(account, transaction)

        // then
        assertNull(violation)
    }

    @Test
    fun `should return INVALID_REPRESENTATIVE when representative is equals previous one`() = runBlocking {
        // when
        val violation = validator.validate(account.copy(representative = block.representative), transaction)

        // then
        assertEquals(TransactionRejectionReason.INVALID_REPRESENTATIVE, violation?.reason)
    }

    @Test
    fun `should return INVALID_BALANCE when balance is different from previous balance`() = runBlocking {
        // when
        val violation = validator.validate(account.copy(balance = AttoAmount(1UL)), transaction)

        // then
        assertEquals(TransactionRejectionReason.INVALID_BALANCE, violation?.reason)
    }
}