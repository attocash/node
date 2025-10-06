package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoVersion
import cash.atto.commons.toAtto
import cash.atto.node.Event
import cash.atto.node.transaction.Transaction
import cash.atto.node.transaction.TransactionSource
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
    /**
     * graalvm seems to have issue with @Version + value class. This issue is not triggered locally
     *
     * Revisit java 25
     * **/
    @Version
    val height: Long,
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
            height = AttoHeight(height.toULong()),
            balance = balance,
            lastTransactionHash = lastTransactionHash,
            lastTransactionTimestamp = lastTransactionTimestamp.toAtto(),
            representativeAlgorithm = representativeAlgorithm,
            representativePublicKey = representativePublicKey,
        )
}

data class AccountUpdated(
    val source: TransactionSource,
    val previousAccount: Account,
    val updatedAccount: Account,
    val transaction: Transaction,
    override val timestamp: Instant = Instant.now(),
) : Event
