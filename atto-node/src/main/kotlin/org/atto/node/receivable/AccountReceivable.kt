package org.atto.node.receivable

import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey

data class AccountReceivable(val hash: AttoHash, val receiverPublicKey: AttoPublicKey, val amount: AttoAmount)