package atto.node.transaction

import atto.protocol.AttoNode
import cash.atto.commons.*
import kotlinx.datetime.Clock
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TransactionConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun genesisTransaction(
        properties: TransactionProperties,
        privateKey: AttoPrivateKey,
        thisNode: AttoNode
    ): Transaction {
        val genesis = properties.genesis

        if (!genesis.isNullOrBlank()) {
            val byteArray = genesis.fromHexToByteArray()

            return AttoTransaction.fromByteBuffer(thisNode.network, AttoByteBuffer.from(byteArray))?.toTransaction()
                ?: throw IllegalStateException("Invalid genesis: ${properties.genesis}")
        }

        logger.info { "No genesis configured. Creating new genesis with this node private key..." }

        val block = AttoOpenBlock(
            version = 0u,
            algorithm = thisNode.algorithm,
            publicKey = privateKey.toPublicKey(),
            balance = AttoAmount.MAX,
            timestamp = Clock.System.now(),
            sendHashAlgorithm = thisNode.algorithm,
            sendHash = AttoHash(ByteArray(32)),
            representative = privateKey.toPublicKey(),
        )

        val transaction = Transaction(
            block = block,
            signature = privateKey.sign(block.hash),
            work = AttoWork.work(thisNode.network, block.timestamp, block.publicKey)
        )

        logger.info {
            "Created ${thisNode.network} genesis transaction ${
                transaction.toAttoTransaction().toByteBuffer().toHex()
            }"
        }

        return transaction
    }
}