package org.atto.node.transaction.convertion

import org.atto.commons.AttoBlockType
import org.atto.commons.AttoTransaction
import org.atto.node.account.Account
import org.atto.node.transaction.Transaction

interface TransactionConversionSupport {
    val blockType: AttoBlockType

    fun toAccountChange(account: Account, transaction: AttoTransaction): Transaction
}