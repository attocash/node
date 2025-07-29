package cash.atto.node.receivable

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.node.AttoRepository
import cash.atto.node.executeAfterCompletion
import cash.atto.node.getCurrentTransaction
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import org.springframework.context.annotation.Primary
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionSynchronization
import java.time.Duration
import java.time.Instant

interface ReceivableRepository : AttoRepository {
    suspend fun saveAll(entities: Iterable<Receivable>): Flow<Receivable>

    suspend fun deleteAllByHash(ids: Iterable<AttoHash>): Int

    suspend fun findById(id: AttoHash): Receivable?

    fun findAllById(ids: Iterable<AttoHash>): Flow<Receivable>

    suspend fun findDesc(
        publicKey: AttoPublicKey,
        minAmount: AttoAmount,
    ): Flow<Receivable>

    suspend fun findAllDesc(
        publicKeys: List<AttoPublicKey>,
        minAmount: AttoAmount,
    ): Flow<Receivable>
}

interface ReceivableCrudRepository :
    CoroutineCrudRepository<Receivable, AttoHash>,
    ReceivableRepository {
    @Modifying
    @Query("DELETE FROM receivable r WHERE r.hash in (:ids)")
    override suspend fun deleteAllByHash(ids: Iterable<AttoHash>): Int

    @Query(
        """
            SELECT * FROM receivable t
            WHERE t.receiver_public_key = :publicKey
            AND t.amount >= :minAmount
            ORDER BY t.amount DESC
        """,
    )
    override suspend fun findDesc(
        publicKey: AttoPublicKey,
        minAmount: AttoAmount,
    ): Flow<Receivable>

    @Query(
        """
            SELECT * FROM receivable t
            WHERE t.receiver_public_key in (:publicKeys)
            AND t.amount >= :minAmount
            ORDER BY t.amount DESC
        """,
    )
    override suspend fun findAllDesc(
        publicKeys: List<AttoPublicKey>,
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

    override suspend fun saveAll(entities: Iterable<Receivable>): Flow<Receivable> =
        receivableCrudRepository.saveAll(entities).onEach {
            val saved = it.copy(persistedAt = Instant.now())

            getCurrentTransaction()!!.apply {
                this.unbindResourceIfPossible(saved.hash)
                this.bindResource(saved.hash, saved)
            }

            executeAfterCompletion { status ->
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    cache[saved.hash] = saved
                }
            }
        }

    override suspend fun deleteAllByHash(ids: Iterable<AttoHash>): Int {
        ids.forEach {
            getCurrentTransaction()?.unbindResourceIfPossible(it)
            cache.remove(it)
        }
        return receivableCrudRepository.deleteAllByHash(ids)
    }

    override suspend fun findById(id: AttoHash): Receivable? = findAllById(listOf(id)).firstOrNull()

    override fun findAllById(ids: Iterable<AttoHash>): Flow<Receivable> =
        flow {
            val seen = mutableSetOf<AttoHash>()

            ids.forEach { id ->
                val cached = getCurrentTransaction()?.getResource(id) as Receivable? ?: cache[id]
                if (cached != null) {
                    emit(cached)
                    seen += id
                }
            }

            val missing = ids.filterNot { it in seen }

            receivableCrudRepository
                .findAllById(missing)
                .collect { emit(it) }
        }

    override suspend fun findDesc(
        publicKey: AttoPublicKey,
        minAmount: AttoAmount,
    ): Flow<Receivable> = receivableCrudRepository.findDesc(publicKey, minAmount)

    override suspend fun findAllDesc(
        publicKeys: List<AttoPublicKey>,
        minAmount: AttoAmount,
    ): Flow<Receivable> = receivableCrudRepository.findAllDesc(publicKeys, minAmount)

    override suspend fun deleteAll() = receivableCrudRepository.deleteAll()
}
