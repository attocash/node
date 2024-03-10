package atto.node.receivable

import atto.node.AttoRepository
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.flow.Flow
import org.springframework.context.annotation.Primary
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
import java.time.Instant


interface ReceivableRepository : AttoRepository {

    suspend fun save(entity: Receivable): Receivable

    suspend fun delete(entity: Receivable)

    suspend fun findById(id: AttoHash): Receivable?

    suspend fun findAsc(publicKey: AttoPublicKey, minAmount: AttoAmount): Flow<Receivable>

}


interface ReceivableCrudRepository : CoroutineCrudRepository<Receivable, AttoHash>, ReceivableRepository {

    @Query(
        """
            SELECT * FROM receivable t 
            WHERE t.receiver_public_key = :publicKey 
            AND t.amount >= :minAmount 
            ORDER BY t.amount DESC
        """
    )
    override suspend fun findAsc(publicKey: AttoPublicKey, minAmount: AttoAmount): Flow<Receivable>

}


@Primary
@Component
class ReceivableCachedRepository(
    private val receivableCrudRepository: ReceivableCrudRepository
) : ReceivableRepository {
    private val cache = Caffeine.newBuilder()
        .maximumSize(100_000)
        .build<AttoHash, Receivable>()
        .asMap()

    override suspend fun save(entity: Receivable): Receivable {
        executeAfterCommit {
            cache[entity.hash] = entity.copy(persistedAt = Instant.now())
        }
        return receivableCrudRepository.save(entity)
    }

    override suspend fun delete(entity: Receivable) {
        executeAfterCommit {
            cache.remove(entity.hash)
        }
        return receivableCrudRepository.delete(entity)
    }

    override suspend fun findById(id: AttoHash): Receivable? {
        return cache[id] ?: receivableCrudRepository.findById(id)
    }

    override suspend fun findAsc(publicKey: AttoPublicKey, minAmount: AttoAmount): Flow<Receivable> {
        return receivableCrudRepository.findAsc(publicKey, minAmount)
    }

    override suspend fun deleteAll() {
        return receivableCrudRepository.deleteAll()
    }


}