package org.atto.node.receivable

import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.springframework.data.annotation.Id

data class AccountReceivable(
    @Id
    val hash: AttoHash,
    val receiverPublicKey: AttoPublicKey,
    val amount: AttoAmount
)