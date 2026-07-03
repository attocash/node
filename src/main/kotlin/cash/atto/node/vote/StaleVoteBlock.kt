package cash.atto.node.vote

import cash.atto.commons.AttoHash
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table
data class StaleVoteBlock(
    @Id
    val blockHash: AttoHash,
    val createdAt: Instant = Instant.now(),
)
