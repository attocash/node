package org.atto.node.transaction.validation.validator

import kotlinx.coroutines.runBlocking
import org.atto.commons.*
import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.TransactionRejectionReason
import org.atto.protocol.AttoNode
import org.atto.protocol.NodeFeature
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.stream.Stream
import kotlin.random.Random

internal class BlockValidatorTest {

    private val blockValidator = BlockValidator(node);

    @ParameterizedTest
    @MethodSource("provider")
    fun test(account: Account, transaction: Transaction, reason: TransactionRejectionReason?) = runBlocking {
        // when
        val violation = blockValidator.validate(account, transaction)

        // then
        assertEquals(reason, violation?.reason)
    }

    companion object {
        val seed = AttoSeed("0000000000000000000000000000000000000000000000000000000000000000".fromHexToByteArray())
        val privateKey = seed.toPrivateKey(0u)

        val account = Account(
            publicKey = privateKey.toPublicKey(),
            version = 0u,
            height = 2u,
            balance = AttoAmount(0u),
            lastTransactionHash = AttoHash(ByteArray(32)),
            lastTransactionTimestamp = AttoNetwork.INITIAL_INSTANT,
            representative = AttoPublicKey(ByteArray(32))
        )
        val block = AttoSendBlock(
            version = account.version,
            publicKey = privateKey.toPublicKey(),
            height = account.height + 1U,
            balance = AttoAmount(100u),
            timestamp = account.lastTransactionTimestamp.plusSeconds(1),
            previous = account.lastTransactionHash,
            receiverPublicKey = privateKey.toPublicKey(),
            amount = AttoAmount(100u),
        )

        val node = AttoNode(
            network = AttoNetwork.LOCAL,
            protocolVersion = 0u,
            publicKey = AttoPublicKey(Random.nextBytes(ByteArray(32))),
            socketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330),
            features = setOf(NodeFeature.VOTING, NodeFeature.HISTORICAL)
        )

        @JvmStatic
        fun provider(): Stream<Arguments> {
            return Stream.of(
//                Arguments.of(
//                    account,
//                    Transaction(
//                        block,
//                        privateKey.sign(block.hash),
//                        AttoWorks.work(node.network, block.timestamp, block.hash)
//                    ),
//                    null
//                ), // TODO: Uncomment it when year is 2023
                Arguments.of(
                    account.copy(height = account.height - 1U),
                    Transaction(
                        block,
                        privateKey.sign(block.hash),
                        AttoWorks.work(node.network, block.timestamp, block.hash)
                    ),
                    TransactionRejectionReason.PREVIOUS_NOT_FOUND
                ),
                Arguments.of(
                    account.copy(height = account.height + 1U),
                    Transaction(
                        block,
                        privateKey.sign(block.hash),
                        AttoWorks.work(node.network, block.timestamp, block.hash)
                    ),
                    TransactionRejectionReason.OLD_TRANSACTION
                ),
                Arguments.of(
                    account.copy(version = (account.version + 1U).toUShort()),
                    Transaction(
                        block,
                        privateKey.sign(block.hash),
                        AttoWorks.work(node.network, block.timestamp, block.hash)
                    ),
                    TransactionRejectionReason.INVALID_VERSION
                ),
                Arguments.of(
                    account.copy(lastTransactionTimestamp = account.lastTransactionTimestamp.plusSeconds(60)),
                    Transaction(
                        block,
                        privateKey.sign(block.hash),
                        AttoWorks.work(node.network, block.timestamp, block.hash)
                    ),
                    TransactionRejectionReason.INVALID_TIMESTAMP
                ),
                Arguments.of(
                    account,
                    Transaction(
                        block,
                        privateKey.sign(block.hash),
                        AttoWorks.work(
                            node.network,
                            block.timestamp,
                            block.publicKey // WRONG WORK HASH. It should be block hash not block public key
                        )
                    ),
                    TransactionRejectionReason.INVALID_TRANSACTION
                )
            )
        }
    }
}