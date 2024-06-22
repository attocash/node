package cash.atto.node.bootstrap.unchecked

import cash.atto.commons.*
import cash.atto.node.transaction.Transaction
import org.springframework.data.annotation.Id
import org.springframework.data.domain.Persistable
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table
data class UncheckedTransaction(
    val block: AttoBlock,
    val signature: AttoSignature,
    val work: AttoWork,
    var receivedAt: Instant,
    var persistedAt: Instant? = null,
) : Persistable<AttoHash> {
    @Id
    val hash = block.hash

    val publicKey = block.publicKey

    val height = block.height

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
