package atto.node.transaction

import atto.protocol.AttoNode
import cash.atto.commons.AttoAlgorithm
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
        val privateKey = AttoPrivateKey.generate()
        val node = AttoNode(
            network = network,
            protocolVersion = 0U,
            algorithm = AttoAlgorithm.V1,
            publicKey = privateKey.toPublicKey(),
            socketAddress = InetSocketAddress(8080),
            features = setOf()
        )
        val configuration = TransactionConfiguration()

        // when
        val genesisTransaction = configuration.genesisTransaction(properties, privateKey, node)

        // then
        assertTrue(genesisTransaction.toAttoTransaction().isValid(network))
    }
}