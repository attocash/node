package atto.node.transaction

import atto.node.receivable.Receivable
import atto.node.receivable.ReceivableService
import atto.protocol.AttoNode
import cash.atto.commons.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import mu.KotlinLogging
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import java.time.Instant

@Configuration
class TransactionConfiguration(
    private val transactionRepository: TransactionRepository,
    private val receivableService: ReceivableService,
    private val transactionService: TransactionService,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    @DependsOn("flywayInitializer")
    fun genesisTransaction(
        properties: TransactionProperties,
        privateKey: AttoPrivateKey,
        thisNode: AttoNode,
    ): Transaction {
        val genesis = properties.genesis

        val genesisTransaction =
            if (!genesis.isNullOrBlank()) {
                val byteArray = genesis.fromHexToByteArray()

                AttoTransaction.fromByteBuffer(thisNode.network, AttoByteBuffer.from(byteArray))?.toTransaction()
                    ?: throw IllegalStateException("Invalid genesis: ${properties.genesis}")
            } else {
                logger.info { "No genesis configured. Creating new genesis with this node private key..." }
                createGenesis(privateKey, thisNode)
            }

        initializeDatabase(genesisTransaction, thisNode)

        return genesisTransaction
    }

    fun initializeDatabase(
        genesisTransaction: Transaction,
        thisNode: AttoNode,
    ) = runBlocking {
        val anyAccountChange = transactionRepository.getLastSample(1).toList()
        if (anyAccountChange.isEmpty()) {
            val block = genesisTransaction.block as AttoOpenBlock

            val transaction =
                Transaction(
                    block = block,
                    signature = genesisTransaction.signature,
                    work = genesisTransaction.work,
                    receivedAt = Instant.now(),
                )

            val receivable =
                Receivable(
                    hash = block.sendHash,
                    version = block.version,
                    algorithm = block.algorithm,
                    receiverAlgorithm = block.algorithm,
                    receiverPublicKey = block.publicKey,
                    amount = block.balance,
                )

            receivableService.save(receivable)

            transactionService.save(TransactionSaveSource.BOOTSTRAP, transaction)

            val network = thisNode.network
            val hash = genesisTransaction.hash
            logger.info { "Initialized $network database with genesis transaction hash $hash" }
        } else if (transactionRepository.findFirstByPublicKey(genesisTransaction.block.publicKey) == null) {
            throw IllegalStateException("Database initialized but doesn't contains current genesis account")
        }
    }

    internal fun createGenesis(
        privateKey: AttoPrivateKey,
        thisNode: AttoNode,
    ): Transaction {
        val block =
            AttoOpenBlock(
                version = 0u,
                algorithm = thisNode.algorithm,
                publicKey = privateKey.toPublicKey(),
                balance = AttoAmount.MAX,
                timestamp = Clock.System.now(),
                sendHashAlgorithm = thisNode.algorithm,
                sendHash = AttoHash(ByteArray(32)),
                representative = privateKey.toPublicKey(),
            )

        val transaction =
            Transaction(
                block = block,
                signature = privateKey.sign(block.hash),
                work = AttoWork.work(thisNode.network, block.timestamp, block.publicKey),
            )

        logger.info {
            "Created ${thisNode.network} genesis transaction ${
                transaction.toAttoTransaction().toByteBuffer().toHex()
            }"
        }

        return transaction
    }
}
