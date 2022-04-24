package org.atto.node.transaction

import org.atto.commons.AttoSendBlock
import org.atto.commons.ReceiveSupportBlock
import org.atto.commons.RepresentativeSupportBlock
import org.atto.node.account.Account
import org.atto.node.account.AccountService
import org.atto.node.receivable.AccountReceivable
import org.atto.node.receivable.AccountReceivableService
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TransactionService(
    private val accountService: AccountService,
    private val accountReceivableService: AccountReceivableService,
    private val transactionRepository: TransactionRepository
) {

    @Transactional
    suspend fun save(account: Account, transaction: Transaction) {
        val block = transaction.block

        account.version = block.version
        account.height = block.height
        account.balance = block.balance
        account.lastHash = block.hash
        account.lastTimestamp = block.timestamp

        if (block is RepresentativeSupportBlock) {
            account.representative = block.representative
        }

        accountService.save(account)
        transactionRepository.save(transaction)

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
}