package org.atto.node.transaction

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.commons.*
import org.atto.protocol.Node
import org.atto.protocol.network.codec.transaction.TransactionCodec
import org.atto.protocol.transaction.Transaction
import org.atto.protocol.transaction.TransactionStatus
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant
import javax.annotation.PostConstruct


@Configuration
class TransactionConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun genesisTransaction(
        properties: TransactionProperties,
        transactionCodec: TransactionCodec,
        privateKey: AttoPrivateKey,
        thisNode: Node
    ): Transaction {
        val genesis = properties.genesis

        if (genesis != null) {
            val byteArray = genesis.fromHexToByteArray()
            val transaction = transactionCodec.fromByteArray(byteArray)

            if (transaction != null) {
                return transaction.copy(status = TransactionStatus.CONFIRMED)
            }

            throw IllegalStateException("Invalid genesis: ${properties.genesis}")
        }

        logger.info { "No genesis configured. Creating new genesis with this node private key..." }

        val block = AttoBlock(
            type = AttoBlockType.OPEN,
            version = 0u,
            publicKey = privateKey.toPublicKey(),
            height = 0u,
            previous = AttoHash(ByteArray(32)),
            representative = privateKey.toPublicKey(),
            link = AttoLink.from(AttoHash(ByteArray(32))),
            balance = AttoAmount.max,
            amount = AttoAmount.max,
            timestamp = Instant.now()
        )

        val transaction = Transaction(
            block = block,
            signature = privateKey.sign(block.getHash().value),
            work = AttoWork.work(block.publicKey, thisNode.network),
            status = TransactionStatus.CONFIRMED,
            receivedTimestamp = Instant.now()
        )

        logger.info { "Created genesis transaction ${transactionCodec.toByteArray(transaction).toHex()}" }

        return transaction
    }

    @Component
    @Order(Integer.MIN_VALUE)
    class GenesisChecker(
        val thisNode: Node,
        val genesisTransaction: Transaction,
        val transactionRepository: TransactionRepository
    ) {
        private val logger = KotlinLogging.logger {}

        @PostConstruct
        fun check() = runBlocking {
            val anyTransaction = transactionRepository.findAnyTransaction()
            if (anyTransaction == null) {
                transactionRepository.save(genesisTransaction)
                val network = thisNode.network
                val hash = genesisTransaction.hash
                logger.info { "Initialized $network database with genesis transaction hash $hash" }
            } else if (transactionRepository.findLastConfirmedByPublicKeyId(genesisTransaction.block.publicKey) == null) {
                throw IllegalStateException("Database initialized but doesn't contains current genesis account")
            }
        }

    }
}