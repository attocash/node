package cash.atto.node.receivable

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.node.AttoRepository
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.flow.Flow
import org.springframework.context.annotation.Primary
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionSynchronization
import java.time.Duration
import java.time.Instant

interface ReceivableRepository : AttoRepository {
    suspend fun save(entity: Receivable): Receivable

    suspend fun delete(hash: AttoHash): Int

    suspend fun findById(id: AttoHash): Receivable?

    suspend fun findAsc(
        publicKey: AttoPublicKey,
        minAmount: AttoAmount,
    ): Flow<Receivable>
}

interface ReceivableCrudRepository :
    CoroutineCrudRepository<Receivable, AttoHash>,
    ReceivableRepository {
    @Modifying
    @Query("DELETE FROM receivable r WHERE r.hash = :hash")
    override suspend fun delete(hash: AttoHash): Int

    @Query(
        """
            SELECT * FROM receivable t
            WHERE t.receiver_public_key = :publicKey
            AND t.amount >= :minAmount
            ORDER BY t.amount DESC
        """,
    )
    override suspend fun findAsc(
        publicKey: AttoPublicKey,
        minAmount: AttoAmount,
    ): Flow<Receivable>
}

@Primary
@Component
class ReceivableCachedRepository(
    private val receivableCrudRepository: ReceivableCrudRepository,
) : ReceivableRepository {
    private val cache =
        Caffeine
            .newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build<AttoHash, Receivable>()
            .asMap()

    override suspend fun save(entity: Receivable): Receivable {
        val saved = receivableCrudRepository.save(entity)

        cache[entity.hash] = saved.copy(persistedAt = Instant.now())

        executeAfterCompletion { status ->
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
                cache.remove(entity.hash)
            }
        }

        return saved
    }

    override suspend fun delete(hash: AttoHash): Int {
        cache.remove(hash)
        return receivableCrudRepository.delete(hash)
    }

    override suspend fun findById(id: AttoHash): Receivable? = cache[id] ?: receivableCrudRepository.findById(id)

    override suspend fun findAsc(
        publicKey: AttoPublicKey,
        minAmount: AttoAmount,
    ): Flow<Receivable> = receivableCrudRepository.findAsc(publicKey, minAmount)

    override suspend fun deleteAll() = receivableCrudRepository.deleteAll()
}
