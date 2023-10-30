package atto.node.transaction

import atto.node.network.codec.TransactionCodec
import atto.protocol.AttoNode
import atto.protocol.network.codec.transaction.AttoTransactionCodec
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress

class TransactionConfigurationTest {

    @Test
    fun `should create genesis transaction when no genesis transaction is configured`() {
        // given
        val network = AttoNetwork.LOCAL
        val properties = TransactionProperties()
        val codec = TransactionCodec(AttoTransactionCodec(network))
        val privateKey = AttoPrivateKey.generate()
        val node = AttoNode(
            network = network,
            protocolVersion = 0U,
            publicKey = privateKey.toPublicKey(),
            socketAddress = InetSocketAddress(8080),
            features = setOf()
        )
        val configuration = TransactionConfiguration()

        // when
        val genesisTransaction = configuration.genesisTransaction(properties, codec, privateKey, node)

        // then
        assertTrue(genesisTransaction.toAttoTransaction().isValid(network))
    }
}