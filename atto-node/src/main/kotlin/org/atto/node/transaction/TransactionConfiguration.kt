package org.atto.node.transaction

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.commons.*
import org.atto.node.account.AccountRepository
import org.atto.node.network.codec.TransactionCodec
import org.atto.protocol.AttoNode
import org.flywaydb.core.Flyway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import java.time.Instant

@Configuration
class TransactionConfiguration {
    private val logger = KotlinLogging.logger {}

    @Bean
    fun genesisAttoTransaction(
        properties: TransactionProperties,
        transactionCodec: TransactionCodec,
        privateKey: AttoPrivateKey,
        thisNode: AttoNode
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
            balance = AttoAmount.max,
            timestamp = Instant.now(),
            sendHash = AttoHash(ByteArray(32)),
            representative = privateKey.toPublicKey(),
        )

        val transaction = Transaction(
            block = block,
            signature = privateKey.sign(block.hash.value),
            work = AttoWork.work(thisNode.network, block.timestamp, block.publicKey)
        )

        logger.info { "Created genesis transaction ${transactionCodec.toByteBuffer(transaction).toHex()}" }

        return transaction
    }

    @Component
    class GenesisInitializer(
        val flyway: Flyway,
        val thisNode: AttoNode,
        val accountRepository: AccountRepository,
        val genesisTransaction: Transaction,
        val transactionService: TransactionService,
        val transactionRepository: TransactionRepository,
    ) {
        private val logger = KotlinLogging.logger {}

        @PostConstruct
        fun init() = runBlocking {
            val anyAccountChange = transactionRepository.findFirstBy()
            if (anyAccountChange == null) {
                val block = genesisTransaction.block as AttoOpenBlock

                val transaction = Transaction(
                    block = block,
                    signature = genesisTransaction.signature,
                    work = genesisTransaction.work,
                    receivedAt = Instant.now(),
                )

                transactionService.save(transaction)

                val network = thisNode.network
                val hash = genesisTransaction.hash
                logger.info { "Initialized $network database with genesis transaction hash $hash" }
            } else if (transactionRepository.findFirstByPublicKey(genesisTransaction.block.publicKey) == null) {
                throw IllegalStateException("Database initialized but doesn't contains current genesis account")
            }
        }

    }
}