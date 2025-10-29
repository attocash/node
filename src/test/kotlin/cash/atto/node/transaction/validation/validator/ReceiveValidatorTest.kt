package cash.atto.node.transaction.validation.validator

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.sign
import cash.atto.commons.toAtto
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toJavaInstant
import cash.atto.commons.toPublicKey
import cash.atto.commons.worker.AttoWorker
import cash.atto.commons.worker.cpu
import cash.atto.node.account.Account
import cash.atto.node.receivable.Receivable
import cash.atto.node.receivable.ReceivableRepository
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionRejectionReason
import cash.atto.protocol.AttoNode
import cash.atto.protocol.NodeFeature
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.net.URI
import kotlin.random.Random

internal class ReceiveValidatorTest {
    val privateKey = AttoPrivateKey.generate()

    val account =
        Account(
            publicKey = privateKey.toPublicKey(),
            network = AttoNetwork.LOCAL,
            algorithm = AttoAlgorithm.V1,
            version = 0U.toAttoVersion(),
            height = 2,
            balance = AttoAmount(0u),
            lastTransactionHash = AttoHash(ByteArray(32)),
            lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT.toJavaInstant(),
            representativeAlgorithm = AttoAlgorithm.V1,
            representativePublicKey = AttoPublicKey(ByteArray(32)),
        )
    val block =
        AttoReceiveBlock(
            network = AttoNetwork.LOCAL,
            version = account.version,
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            height = AttoHeight((account.height + 1).toULong()),
            balance = AttoAmount(10U),
            timestamp = account.lastTransactionTimestamp.plusSeconds(1).toAtto(),
            previous = account.lastTransactionHash,
            sendHashAlgorithm = AttoAlgorithm.V1,
            sendHash = AttoHash(ByteArray(32)),
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

    @Test
    fun `should validate`() =
        runBlocking {
            // given
            val receivable =
                Receivable(
                    network = block.network,
                    hash = block.sendHash,
                    version = 0U.toAttoVersion(),
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(Random.nextBytes(32)),
                    timestamp = block.timestamp.toJavaInstant().minusSeconds(1),
                    receiverAlgorithm = block.algorithm,
                    receiverPublicKey = block.publicKey,
                    amount = block.balance - account.balance,
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
    fun `should return SEND_NOT_FOUND when receivable is not found`() =
        runBlocking {
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
    fun `should return INVALID_RECEIVER when previous is different`() =
        runBlocking {
            // given
            val byteArray = ByteArray(32)
            byteArray.fill(1)
            val receivable =
                Receivable(
                    network = block.network,
                    hash = block.sendHash,
                    version = 0U.toAttoVersion(),
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(Random.nextBytes(32)),
                    timestamp = block.timestamp.toJavaInstant(),
                    receiverAlgorithm = block.algorithm,
                    receiverPublicKey = AttoPublicKey(byteArray),
                    amount = block.balance - account.balance,
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
    fun `should return INVALID_BALANCE when previous is different`() =
        runBlocking {
            // given
            val receivable =
                Receivable(
                    network = block.network,
                    hash = block.sendHash,
                    version = 0U.toAttoVersion(),
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(Random.nextBytes(32)),
                    timestamp = block.timestamp.toJavaInstant().minusSeconds(1),
                    receiverAlgorithm = block.algorithm,
                    receiverPublicKey = account.publicKey,
                    amount = AttoAmount(2UL),
                )

            val receivableRepository = mockk<ReceivableRepository>()
            coEvery { receivableRepository.findById(block.sendHash) } returns receivable

            val validator = ReceiveValidator(receivableRepository)

            // when
            val violation = validator.validate(account, transaction)

            // then
            assertEquals(TransactionRejectionReason.INVALID_BALANCE, violation?.reason)
        }

    @Test
    fun `should return INVALID_TIMESTAMP when Receivable timestamp is ahead of transaction timestamp`() =
        runBlocking {
            // given
            val receivable =
                Receivable(
                    network = block.network,
                    hash = block.sendHash,
                    version = 0U.toAttoVersion(),
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(Random.nextBytes(32)),
                    timestamp = block.timestamp.toJavaInstant(),
                    receiverAlgorithm = block.algorithm,
                    receiverPublicKey = account.publicKey,
                    amount = block.balance - account.balance,
                )

            val receivableRepository = mockk<ReceivableRepository>()
            coEvery { receivableRepository.findById(block.sendHash) } returns receivable

            val validator = ReceiveValidator(receivableRepository)

            // when
            val violation = validator.validate(account, transaction)

            // then
            assertEquals(TransactionRejectionReason.INVALID_TIMESTAMP, violation?.reason)
        }
}
