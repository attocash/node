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
    suspend fun saveAll(transactions: List<Transaction>) {
        transactionRepository.saveAll(transactions).collect { transaction ->
            logger.debug { "Saved $transaction" }
        }

        val receivables =
            transactions
                .asSequence()
                .map { it.block }
                .filterIsInstance<AttoSendBlock>()
                .map { it.toReceivable().toReceivable() }
                .toList()

        receivableService.saveAll(receivables)

        val received =
            transactions
                .asSequence()
                .map { it.block }
                .filterIsInstance<ReceiveSupport>()
                .map { it.sendHash }
                .toList()

        receivableService.deleteAll(received)
    }
}
