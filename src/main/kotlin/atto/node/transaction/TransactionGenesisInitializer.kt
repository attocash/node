package atto.node.transaction

import atto.node.receivable.Receivable
import atto.node.receivable.ReceivableService
import cash.atto.commons.AttoOpenBlock
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.context.annotation.DependsOn
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@DependsOn("flyway")
class TransactionGenesisInitializer(
    val thisNode: atto.protocol.AttoNode,
    val genesisTransaction: Transaction,
    val transactionService: TransactionService,
    val receivableService: ReceivableService,
    val transactionRepository: TransactionRepository,
) {
    private val logger = KotlinLogging.logger {}

    @PostConstruct
    fun init() = runBlocking {
        val anyAccountChange = transactionRepository.getLastSample(1).toList()
        if (anyAccountChange.isEmpty()) {
            val block = genesisTransaction.block as AttoOpenBlock

            val transaction = Transaction(
                block = block,
                signature = genesisTransaction.signature,
                work = genesisTransaction.work,
                receivedAt = Instant.now(),
            )

            val receivable = Receivable(
                hash = block.sendHash,
                receiverPublicKey = block.publicKey,
                amount = block.balance
            )

            receivableService.save(receivable)

            transactionService.save(transaction)

            val network = thisNode.network
            val hash = genesisTransaction.hash
            logger.info { "Initialized $network database with genesis transaction hash $hash" }
        } else if (transactionRepository.findFirstByPublicKey(genesisTransaction.block.publicKey) == null) {
            throw IllegalStateException("Database initialized but doesn't contains current genesis account")
        }
    }

}