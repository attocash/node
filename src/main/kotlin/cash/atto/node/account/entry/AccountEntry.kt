package cash.atto.node.account.entry

import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlockType
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.node.Event
import kotlinx.datetime.toJavaInstant
import kotlinx.datetime.toKotlinInstant
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import java.time.Instant

data class AccountEntry(
    @Id
    val hash: AttoHash,
    val algorithm: AttoAlgorithm,
    val publicKey: AttoPublicKey,
    val height: AttoHeight,
    val blockType: AttoBlockType,
    val subjectAlgorithm: AttoAlgorithm,
    val subjectPublicKey: AttoPublicKey,
    val previousBalance: AttoAmount,
    val balance: AttoAmount,
    val timestamp: Instant,
    val persistedAt: Instant? = null,
) : Persistable<AttoHash> {

    override fun getId(): AttoHash = hash

    override fun isNew(): Boolean = persistedAt == null
}

fun AccountEntry.toAtto(): AttoAccountEntry =
    AttoAccountEntry(
        hash = hash,
        algorithm = algorithm,
        publicKey = publicKey,
        height = height,
        blockType = blockType,
        subjectAlgorithm = subjectAlgorithm,
        subjectPublicKey = subjectPublicKey,
        previousBalance = previousBalance,
        balance = balance,
        timestamp = timestamp.toKotlinInstant(),
    )

fun AttoAccountEntry.toEntity(): AccountEntry =
    AccountEntry(
        hash = hash,
        algorithm = algorithm,
        publicKey = publicKey,
        height = height,
        blockType = blockType,
        subjectAlgorithm = subjectAlgorithm,
        subjectPublicKey = subjectPublicKey,
        previousBalance = previousBalance,
        balance = balance,
        timestamp = timestamp.toJavaInstant(),
    )

data class AccountEntrySaved(
    val entry: AccountEntry,
) : Event
