package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.AttoBlock
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.PreviousSupport
import cash.atto.node.transaction.Transaction
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table
data class UncheckedTransaction(
    @Transient
    val block: AttoBlock,
    @Transient
    val signature: AttoSignature,
    @Transient
    val work: AttoWork,
    val receivedAt: Instant = Instant.now(),
    val persistedAt: Instant? = null,
) : Persistable<AttoHash> {
    @Id
    val hash = block.hash

    val publicKey = block.publicKey
    val height = block.height

    @Transient
    val previous = if (block is PreviousSupport) block.previous else null

    override fun getId(): AttoHash = hash

    override fun isNew(): Boolean = persistedAt == null

    fun toTransaction(): Transaction =
        Transaction(
            block = block,
            signature = signature,
            work = work,
            receivedAt = receivedAt,
        )

    override fun toString(): String =
        "UncheckedTransaction(block=$block, signature=$signature, work=$work, receivedAt=$receivedAt, persistedAt=$persistedAt, " +
            "hash=$hash, publicKey=$publicKey, height=$height)"
}

fun Transaction.toUncheckedTransaction(): UncheckedTransaction =
    UncheckedTransaction(
        block = block,
        signature = signature,
        work = work,
        receivedAt = receivedAt,
    )
