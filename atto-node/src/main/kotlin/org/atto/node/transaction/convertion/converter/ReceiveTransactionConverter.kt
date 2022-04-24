package org.atto.node.transaction.convertion.converter

import org.atto.commons.AttoBlockType
import org.atto.commons.AttoReceiveBlock
import org.atto.commons.AttoTransaction
import org.atto.node.account.Account
import org.atto.node.transaction.Transaction
import org.atto.node.transaction.convertion.TransactionConversionSupport
import org.springframework.stereotype.Component

@Component
class ReceiveTransactionConverter : TransactionConversionSupport {
    override val blockType = AttoBlockType.RECEIVE

    override fun toAccountChange(account: Account, transaction: AttoTransaction): Transaction {
        val block = transaction.block as AttoReceiveBlock

        return Transaction(
            block = block,
            signature = transaction.signature,
            work = transaction.work
        )
    }

}