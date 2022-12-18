package org.atto.node.transaction

import mu.KotlinLogging
import org.atto.commons.AttoOpenBlock
import org.atto.commons.AttoSendBlock
import org.atto.commons.ReceiveSupportBlock
import org.atto.commons.RepresentativeSupportBlock
import org.atto.node.account.Account
import org.atto.node.account.AccountRepository
import org.atto.node.account.AccountService
import org.atto.node.receivable.AccountReceivable
import org.atto.node.receivable.AccountReceivableService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionService(
    private val accountRepository: AccountRepository,
    private val accountService: AccountService,
    private val accountReceivableService: AccountReceivableService,
    private val transactionRepository: TransactionRepository,
) {
    private val logger = KotlinLogging.logger {}

    @Transactional
    suspend fun save(transaction: Transaction) {
        val block = transaction.block

        val account = getAccount(transaction)
        account.version = block.version
        account.height = block.height
        account.balance = block.balance
        account.lastTransactionHash = block.hash
        account.lastTransactionTimestamp = block.timestamp

        if (block is RepresentativeSupportBlock) {
            account.representative = block.representative
        }

        accountService.save(account)
        transactionRepository.save(transaction)
        logger.debug { "Saved $transaction" }

        if (block is AttoSendBlock) {
            val receivable = AccountReceivable(
                hash = block.hash,
                receiverPublicKey = block.receiverPublicKey,
                amount = block.amount
            )
            accountReceivableService.save(receivable)
        } else if (block is ReceiveSupportBlock) {
            accountReceivableService.delete(block.sendHash)
        }
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