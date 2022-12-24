package org.atto.node.transaction

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.atto.commons.AttoOpenBlock
import org.atto.protocol.AttoNode
import org.springframework.context.annotation.DependsOn
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import java.time.Instant

@Component
@DependsOn("flyway")
class TransactionGenesisInitializer(
    val thisNode: AttoNode,
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