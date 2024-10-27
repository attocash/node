package cash.atto.node.transaction

import cash.atto.commons.AttoSendBlock
import cash.atto.commons.ReceiveSupport
import cash.atto.commons.toReceivable
import cash.atto.node.receivable.ReceivableService
import cash.atto.node.receivable.toReceivable
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionService(
    private val receivableService: ReceivableService,
    private val transactionRepository: TransactionRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    suspend fun save(
        source: TransactionSource,
        transaction: Transaction,
    ) {
        val block = transaction.block

        transactionRepository.save(transaction)
        logger.debug { "Saved $transaction" }

        if (block is AttoSendBlock) {
            val receivable = block.toReceivable().toReceivable()
            receivableService.save(receivable)
        } else if (block is ReceiveSupport) {
            receivableService.delete(block.sendHash)
        }
    }
}
