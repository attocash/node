package cash.atto.node.transaction

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoOpenBlock
import cash.atto.commons.AttoSigner
import cash.atto.commons.AttoTransaction
import cash.atto.commons.fromHexToByteArray
import cash.atto.commons.toAttoVersion
import cash.atto.commons.toBuffer
import cash.atto.commons.toHex
import cash.atto.commons.worker.AttoWorker
import cash.atto.commons.worker.cpu
import cash.atto.node.account.AccountService
import cash.atto.node.receivable.Receivable
import cash.atto.node.receivable.ReceivableService
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.datetime.toJavaInstant
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.DependsOn
import java.time.Instant

@Configuration
class TransactionConfiguration(
    private val transactionRepository: TransactionRepository,
    private val receivableService: ReceivableService,
    private val accountService: AccountService,
) {
    private val logger = KotlinLogging.logger {}

    @Bean
    @DependsOn("flywayInitializer")
    fun genesisTransaction(
        properties: TransactionProperties,
        signer: AttoSigner,
        thisNode: AttoNode,
    ): Transaction {
        val genesis = properties.genesis

        val genesisTransaction =
            if (!genesis.isNullOrBlank()) {
                val byteArray = genesis.fromHexToByteArray()

                logger.info { "Found genesis transaction ${byteArray.toHex()}" }

                AttoTransaction.fromBuffer(byteArray.toBuffer())?.toTransaction()
                    ?: throw IllegalStateException("Invalid genesis: ${properties.genesis}")
            } else {
                logger.info { "No genesis found. Creating new genesis with this node signer..." }
                createGenesis(signer, thisNode)
            }

        if (genesisTransaction.block.network != thisNode.network) {
            throw IllegalStateException("${genesisTransaction.block.network} is an invalid genesis network: ${properties.genesis}")
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
                    publicKey = block.publicKey,
                    timestamp = block.timestamp.toJavaInstant(),
                    receiverAlgorithm = block.algorithm,
                    receiverPublicKey = block.publicKey,
                    amount = block.balance,
                )

            receivableService.save(receivable)

            accountService.add(TransactionSource.BOOTSTRAP, transaction)

            val network = thisNode.network
            val hash = genesisTransaction.hash
            logger.info { "Initialized $network database with genesis transaction hash $hash" }
        } else if (transactionRepository.findFirstByPublicKey(genesisTransaction.block.publicKey) == null) {
            throw IllegalStateException("Database initialized but doesn't contains current genesis account")
        }
    }

    internal fun createGenesis(
        signer: AttoSigner,
        thisNode: AttoNode,
    ): Transaction {
        val block =
            AttoOpenBlock(
                version = 0U.toAttoVersion(),
                network = thisNode.network,
                algorithm = thisNode.algorithm,
                publicKey = signer.publicKey,
                balance = AttoAmount.MAX,
                timestamp = Clock.System.now(),
                sendHashAlgorithm = thisNode.algorithm,
                sendHash = AttoHash(ByteArray(32)),
                representativeAlgorithm = thisNode.algorithm,
                representativePublicKey = signer.publicKey,
            )

        val transaction =
            Transaction(
                block = block,
                signature = runBlocking { signer.sign(block) },
                work = runBlocking { AttoWorker.cpu().work(block) },
            )

        logger.info { "Created ${thisNode.network} genesis transaction ${transaction.toAttoTransaction().toHex()}" }

        return transaction
    }
}
