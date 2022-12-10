package org.atto.node.account

import org.atto.commons.AttoAccount
import org.atto.commons.AttoAmount
import org.atto.commons.AttoHash
import org.atto.commons.AttoPublicKey
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class Account(
    @Id
    val publicKey: AttoPublicKey,
    var version: UShort,
    var height: ULong,
    var balance: AttoAmount,
    var lastTransactionHash: AttoHash,
    var lastTransactionTimestamp: Instant,
    var representative: AttoPublicKey,

    var persistedAt: Instant? = null,
    var updatedAt: Instant? = null,

    ) : Persistable<AttoPublicKey> {

    override fun getId(): AttoPublicKey {
        return publicKey
    }

    override fun isNew(): Boolean {
        return persistedAt == null
    }

    fun toAttoAccount(): AttoAccount {
        return AttoAccount(
            publicKey = publicKey,
            version = version,
            height = height,
            balance = balance,
            lastTransactionHash = lastTransactionHash,
            lastTransactionTimestamp = lastTransactionTimestamp,
            representative = representative,
        )
    }
}