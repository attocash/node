package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoVersion
import cash.atto.node.Event
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
import kotlinx.datetime.toKotlinInstant
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.Version
import org.springframework.data.domain.Persistable
import java.time.Instant

data class Account(
    @Id
    val publicKey: AttoPublicKey,
    val network: AttoNetwork,
    val version: AttoVersion,
    val algorithm: AttoAlgorithm,
    @Version
    val height: AttoHeight,
    val balance: AttoAmount,
    val lastTransactionTimestamp: Instant,
    val lastTransactionHash: AttoHash,
    val representativeAlgorithm: AttoAlgorithm,
    val representativePublicKey: AttoPublicKey,
    val persistedAt: Instant? = null,
    val updatedAt: Instant? = null,
) : Persistable<AttoPublicKey> {
    override fun getId(): AttoPublicKey = publicKey

    override fun isNew(): Boolean = persistedAt == null

    fun toAttoAccount(): AttoAccount =
        AttoAccount(
            publicKey = publicKey,
            network = network,
            version = version,
            algorithm = algorithm,
            height = height,
            balance = balance,
            lastTransactionHash = lastTransactionHash,
            lastTransactionTimestamp = lastTransactionTimestamp.toKotlinInstant(),
            representativeAlgorithm = representativeAlgorithm,
            representativePublicKey = representativePublicKey,
        )
}

data class AccountUpdated(
    val source: TransactionSource,
    val previousAccount: Account,
    val updatedAccount: Account,
    val transaction: Transaction,
) : Event
