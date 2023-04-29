package atto.node.account

import com.fasterxml.jackson.annotation.JsonIgnore
import atto.commons.AttoAccount
import atto.commons.AttoAmount
import atto.commons.AttoHash
import atto.commons.AttoPublicKey
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class Account(
    @Id
    val publicKey: AttoPublicKey,
    var version: UShort,
    var height: ULong,
    var balance: AttoAmount,
    var lastTransactionTimestamp: Instant,
    var lastTransactionHash: AttoHash,
    var representative: AttoPublicKey,

    var persistedAt: Instant? = null,
    var updatedAt: Instant? = null,

    ) : Persistable<AttoPublicKey> {

    @JsonIgnore
    override fun getId(): AttoPublicKey {
        return publicKey
    }

    @JsonIgnore
    override fun isNew(): Boolean {
        return persistedAt == null
    }

    fun toAttoAccount(): atto.commons.AttoAccount {
        return atto.commons.AttoAccount(
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