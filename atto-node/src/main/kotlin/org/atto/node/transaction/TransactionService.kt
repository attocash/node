package org.atto.node.transaction

import mu.KotlinLogging
import org.atto.commons.AttoOpenBlock
import org.atto.commons.AttoSendBlock
import org.atto.commons.ReceiveSupportBlock
import org.atto.commons.RepresentativeSupportBlock
import org.atto.node.EventPublisher
import org.atto.node.account.Account
import org.atto.node.account.AccountRepository
import org.atto.node.account.AccountService
import org.atto.node.receivable.Receivable
import org.atto.node.receivable.ReceivableService
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
            lastTransactionTimestamp = block.timestamp,
            representative = if (block is RepresentativeSupportBlock) block.representative else previousAccount.representative
        )

        accountService.save(updatedAccount)
        transactionRepository.save(transaction)
        logger.debug { "Saved $transaction" }

        if (block is AttoSendBlock) {
            val receivable = Receivable(
                hash = block.hash,
                receiverPublicKey = block.receiverPublicKey,
                amount = block.amount
            )
            receivableService.save(receivable)
        } else if (block is ReceiveSupportBlock) {
            receivableService.delete(block.sendHash)
        }

        eventPublisher.publish(TransactionSaved(updatedAccount, updatedAccount, transaction))

        return SaveTransactionResponse(updatedAccount, updatedAccount, transaction)
    }

    private suspend fun getAccount(transaction: Transaction): Account {
        val block = transaction.block
        if (block is AttoOpenBlock) {
            return Account(
                publicKey = block.publicKey,
                version = block.version,
                height = block.height,
                balance = block.balance,
                lastTransactionHash = block.hash,
                lastTransactionTimestamp = block.timestamp,
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