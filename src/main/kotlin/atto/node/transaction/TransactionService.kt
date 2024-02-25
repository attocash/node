package atto.node.transaction

import atto.node.EventPublisher
import atto.node.account.Account
import atto.node.account.AccountRepository
import atto.node.account.AccountService
import atto.node.receivable.ReceivableService
import atto.node.receivable.toReceivable
import cash.atto.commons.*
import kotlinx.datetime.toJavaInstant
import mu.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional


@Service
class TransactionService(
    private val accountRepository: AccountRepository,
    private val accountService: AccountService,
    private val receivableService: ReceivableService,
    private val transactionRepository: TransactionRepository,
    private val eventPublisher: EventPublisher,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    suspend fun save(transaction: Transaction): SaveTransactionResponse {
        val block = transaction.block

        val previousAccount = getAccount(transaction)
        val updatedAccount = previousAccount.copy(
            version = block.version,
            height = block.height,
            balance = block.balance,
            lastTransactionHash = block.hash,
            lastTransactionTimestamp = block.timestamp.toJavaInstant(),
            representative = if (block is RepresentativeSupport) block.representative else previousAccount.representative
        )

        accountService.save(updatedAccount)
        transactionRepository.save(transaction)
        logger.debug { "Saved $transaction" }

        if (block is AttoSendBlock) {
            val receivable = block.toReceivable().toReceivable()
            receivableService.save(receivable)
        } else if (block is ReceiveSupport) {
            receivableService.delete(block.sendHash)
        }

        eventPublisher.publishAfterCommit(TransactionSaved(previousAccount, updatedAccount, transaction))

        return SaveTransactionResponse(previousAccount, updatedAccount, transaction)
    }

    private suspend fun getAccount(transaction: Transaction): Account {
        val block = transaction.block
        if (block is AttoOpenBlock) {
            return Account(
                publicKey = block.publicKey,
                version = block.version,
                algorithm = block.algorithm,
                height = block.height,
                balance = block.balance,
                lastTransactionHash = block.hash,
                lastTransactionTimestamp = block.timestamp.toJavaInstant(),
                representative = block.representative
            )
        }
        return accountRepository.findById(transaction.publicKey)!!
    }
}

data class SaveTransactionResponse(
    val previousAccount: Account,
    val updatedAccount: Account,
    val transaction: Transaction
)