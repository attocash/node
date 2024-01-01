package atto.node.account

import cash.atto.commons.*
import kotlinx.datetime.toKotlinInstant
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class Account(
    @Id
    val publicKey: AttoPublicKey,
    var version: UShort,
    var algorithm: AttoAlgorithm,
    var height: ULong,
    var balance: AttoAmount,
    var lastTransactionTimestamp: Instant,
    var lastTransactionHash: AttoHash,
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
            algorithm = algorithm,
            height = height,
            balance = balance,
            lastTransactionHash = lastTransactionHash,
            lastTransactionTimestamp = lastTransactionTimestamp.toKotlinInstant(),
            representative = representative,
        )
    }
}