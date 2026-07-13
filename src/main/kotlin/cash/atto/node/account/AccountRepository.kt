package cash.atto.node.account

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toAttoVersion
import cash.atto.node.AttoRepository
import cash.atto.node.RepositoryCacheEntry
import cash.atto.node.getCurrentTransaction
import cash.atto.node.getOrCreateRepositoryTransactionCache
import cash.atto.node.getRepositoryTransactionCache
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import org.springframework.context.annotation.Primary
import org.springframework.context.event.EventListener
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant

interface AccountRepository : AttoRepository {
    fun saveAll(entities: List<Account>): Flow<Account>

    suspend fun findById(id: AttoPublicKey): Account?

    fun findAllById(ids: Iterable<AttoPublicKey>): Flow<Account>
}

interface AccountBulkRepository {
    suspend fun upsertAll(accounts: Collection<Account>): Long
}

interface AccountCrudRepository :
    CoroutineCrudRepository<Account, AttoPublicKey>,
    AccountBulkRepository,
    AccountRepository {
    @Query("SELECT COALESCE(SUM(height), 0) FROM account")
    suspend fun sumHeight(): Long

    @Deprecated("Temporary")
    @Query("SELECT * FROM account ORDER BY balance DESC LIMIT 100")
    suspend fun getTop100(): List<Account>
}

@Primary
@Component
class AccountCachedRepository(
    private val accountCrudRepository: AccountCrudRepository,
) : AccountRepository {
    private val transactionCacheKey = Any()

    private val cache =
        Caffeine
            .newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build<AttoPublicKey, Account>()
            .asMap()

    override fun saveAll(entities: List<Account>): Flow<Account> =
        flow {
            if (entities.isEmpty()) {
                return@flow
            }

            val now = Instant.now()
            val accounts =
                entities.map {
                    it.copy(
                        height = it.height + 1,
                        persistedAt = it.persistedAt ?: now,
                        updatedAt = now,
                    )
                }

            accountCrudRepository.upsertAll(accounts)

            val currentTransaction = getCurrentTransaction()!!
            val transactionCache = currentTransaction.getOrCreateTransactionCache()
            accounts.forEach { saved ->
                transactionCache.put(saved.publicKey, saved)
            }

            accounts.forEach { emit(it) }
        }

    override suspend fun findById(id: AttoPublicKey): Account? = findAllById(listOf(id)).firstOrNull()

    override fun findAllById(ids: Iterable<AttoPublicKey>): Flow<Account> =
        flow {
            val missing = mutableListOf<AttoPublicKey>()
            val transactionCache =
                getCurrentTransaction()?.getRepositoryTransactionCache<AttoPublicKey, Account>(transactionCacheKey)

            for (id in ids) {
                when (val entry = transactionCache?.get(id)) {
                    is RepositoryCacheEntry.Present -> {
                        emit(entry.value)
                        continue
                    }

                    RepositoryCacheEntry.Deleted -> {
                        continue
                    }

                    null -> {
                        Unit
                    }
                }

                if (transactionCache?.cleared == true) {
                    continue
                }
                val cached = cache[id]
                if (cached != null) {
                    emit(cached)
                    continue
                }

                missing += id
            }

            if (missing.isEmpty()) {
                return@flow
            }

            accountCrudRepository.findAllById(missing).collect { account ->
                val cached = putIfNewer(account)
                emit(cached ?: account)
            }
        }

    @Order(Ordered.HIGHEST_PRECEDENCE)
    @EventListener
    fun process(event: AccountUpdated) {
        putIfNewer(event.updatedAccount)
    }

    private fun putIfNewer(account: Account): Account? =
        cache.compute(account.publicKey) { _, existingValue ->
            if (existingValue != null && existingValue.height > account.height) {
                existingValue
            } else {
                account
            }
        }

    override suspend fun deleteAll() {
        accountCrudRepository.deleteAll()
        val currentTransaction = getCurrentTransaction()
        if (currentTransaction == null) {
            cache.clear()
        } else {
            currentTransaction.getOrCreateTransactionCache().clear()
        }
    }

    private fun TransactionSynchronizationManager.getOrCreateTransactionCache() =
        getOrCreateRepositoryTransactionCache<AttoPublicKey, Account>(transactionCacheKey) { cleared, entries ->
            if (cleared) {
                cache.clear()
            }
            entries.forEach { (publicKey, entry) ->
                when (entry) {
                    is RepositoryCacheEntry.Present -> putIfNewer(entry.value)
                    RepositoryCacheEntry.Deleted -> cache.remove(publicKey)
                }
            }
        }
}

suspend fun AccountRepository.getByAlgorithmAndPublicKey(
    algorithm: AttoAlgorithm,
    publicKey: AttoPublicKey,
    network: AttoNetwork,
): Account {
    val account = findById(publicKey)
    if (account != null && account.algorithm == algorithm) {
        return account
    }

    return Account(
        publicKey = publicKey,
        version = 0u.toAttoVersion(),
        network = network,
        algorithm = algorithm,
        height = 0,
        representativeAlgorithm = algorithm,
        representativePublicKey = AttoPublicKey(ByteArray(32)),
        balance = AttoAmount.MIN,
        lastTransactionHash = AttoHash(ByteArray(32)),
        lastTransactionTimestamp = Instant.MIN,
    )
}
