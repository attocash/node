package atto.node.transaction

import atto.node.network.codec.TransactionCodec
import cash.atto.commons.*
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Instant

@Configuration
class TransactionConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun genesisAttoTransaction(
        properties: TransactionProperties,
        transactionCodec: TransactionCodec,
        privateKey: AttoPrivateKey,
        thisNode: atto.protocol.AttoNode
    ): Transaction {
        val genesis = properties.genesis

        if (genesis != null) {
            val byteArray = genesis.fromHexToByteArray()

            return transactionCodec.fromByteBuffer(AttoByteBuffer.from(byteArray))
                ?: throw IllegalStateException("Invalid genesis: ${properties.genesis}")
        }

        logger.info { "No genesis configured. Creating new genesis with this node private key..." }

        val block = AttoOpenBlock(
            version = 0u,
            publicKey = privateKey.toPublicKey(),
            balance = AttoAmount.MAX,
            timestamp = Instant.now(),
            sendHash = AttoHash(ByteArray(32)),
            representative = privateKey.toPublicKey(),
        )

        val transaction = Transaction(
            block = block,
            signature = privateKey.sign(block.hash),
            work = AttoWork.work(thisNode.network, block.timestamp, block.publicKey)
        )

        logger.info { "Created genesis transaction ${transactionCodec.toByteBuffer(transaction).toHex()}" }

        return transaction
    }
}