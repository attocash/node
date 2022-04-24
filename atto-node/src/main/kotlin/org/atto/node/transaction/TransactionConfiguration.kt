package org.atto.node.transaction

import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.commons.*
import org.atto.node.ApplicationProperties
import org.atto.node.account.Account
import org.atto.protocol.AttoNode
import org.atto.protocol.network.codec.transaction.AttoTransactionCodec
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
    fun genesisAttoTransaction(
        properties: ApplicationProperties,
        transactionCodec: AttoTransactionCodec,
        privateKey: AttoPrivateKey,
        thisNode: AttoNode
    ): AttoTransaction {
        val genesis = properties.genesis

        if (genesis != null) {
            val byteArray = genesis.fromHexToByteArray()
            val transaction = transactionCodec.fromByteBuffer(AttoByteBuffer.from(byteArray))

            if (transaction != null) {
                return transaction
            }

            throw IllegalStateException("Invalid genesis: ${properties.genesis}")
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

        val attoTransaction = AttoTransaction(
            block = block,
            signature = privateKey.sign(block.hash.value),
            work = AttoWork.work(block.publicKey, thisNode.network)
        )

        logger.info { "Created genesis transaction ${transactionCodec.toByteBuffer(attoTransaction).toHex()}" }

        return attoTransaction
    }

    @Component
    @Order(Integer.MIN_VALUE)
    class GenesisChecker(
        val thisNode: AttoNode,
        val genesisAttoTransaction: AttoTransaction,
        val transactionService: TransactionService,
        val transactionRepository: TransactionRepository,
    ) {
        private val logger = KotlinLogging.logger {}

        @PostConstruct
        fun check() = runBlocking {
            val anyAccountChange = transactionRepository.findFirst()
            if (anyAccountChange == null) {
                val block = genesisAttoTransaction.block as AttoOpenBlock

                val account = Account(
                    publicKey = block.publicKey,
                    version = 0u,
                    height = 0u,
                    representative = AttoPublicKey(ByteArray(32)),
                    balance = AttoAmount.min,
                    lastHash = AttoHash(ByteArray(32)),
                    lastTimestamp = Instant.MIN
                )

                val transaction = Transaction(
                    block = block,
                    signature = genesisAttoTransaction.signature,
                    work = genesisAttoTransaction.work,
                    receivedTimestamp = Instant.now(),
                )

                transactionService.save(account, transaction)

                val network = thisNode.network
                val hash = genesisAttoTransaction.hash
                logger.info { "Initialized $network database with genesis transaction hash $hash" }
            } else if (transactionRepository.findFirstByPublicKey(genesisAttoTransaction.block.publicKey) == null) {
                throw IllegalStateException("Database initialized but doesn't contains current genesis account")
            }
        }

    }
}