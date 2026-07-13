package cash.atto.node

import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.transaction.NoTransactionException
import org.springframework.transaction.reactive.TransactionSynchronization
import org.springframework.transaction.reactive.TransactionSynchronizationManager
import reactor.core.publisher.Mono

interface AttoRepository {
    suspend fun deleteAll()
}

suspend fun getCurrentTransaction(): TransactionSynchronizationManager? =
    TransactionSynchronizationManager
        .forCurrentTransaction()
        .onErrorResume(NoTransactionException::class.java) { Mono.empty() }
        .awaitFirstOrNull()

internal sealed interface RepositoryCacheEntry<out V> {
    data class Present<V>(
        val value: V,
    ) : RepositoryCacheEntry<V>

    data object Deleted : RepositoryCacheEntry<Nothing>
}

// Transaction-local read-your-writes overlay; repository calls within one reactive transaction are expected to be sequential.
// Shared Caffeine caches are applied only in afterCommit, so rollback leaves them unchanged.
internal class RepositoryTransactionCache<K, V>(
    private val applyChanges: (Boolean, Map<K, RepositoryCacheEntry<V>>) -> Unit,
) {
    private val entries = LinkedHashMap<K, RepositoryCacheEntry<V>>()

    var cleared: Boolean = false
        private set

    operator fun get(key: K): RepositoryCacheEntry<V>? = entries[key]

    fun put(
        key: K,
        value: V,
    ) {
        entries[key] = RepositoryCacheEntry.Present(value)
    }

    fun delete(key: K) {
        entries[key] = RepositoryCacheEntry.Deleted
    }

    fun clear() {
        cleared = true
        entries.clear()
    }

    fun apply() {
        applyChanges(cleared, entries)
    }
}

@Suppress("UNCHECKED_CAST")
internal fun <K, V> TransactionSynchronizationManager.getRepositoryTransactionCache(resourceKey: Any): RepositoryTransactionCache<K, V>? =
    getResource(resourceKey) as RepositoryTransactionCache<K, V>?

internal fun <K, V> TransactionSynchronizationManager.getOrCreateRepositoryTransactionCache(
    resourceKey: Any,
    applyChanges: (Boolean, Map<K, RepositoryCacheEntry<V>>) -> Unit,
): RepositoryTransactionCache<K, V> {
    getRepositoryTransactionCache<K, V>(resourceKey)?.let { return it }

    val transactionCache = RepositoryTransactionCache(applyChanges)
    bindResource(resourceKey, transactionCache)
    try {
        registerSynchronization(
            object : TransactionSynchronization {
                override fun afterCommit(): Mono<Void> = Mono.fromRunnable(transactionCache::apply)
            },
        )
    } catch (exception: RuntimeException) {
        unbindResourceIfPossible(resourceKey)
        throw exception
    }
    return transactionCache
}

suspend fun executeAfterCompletion(callback: (Int) -> Unit) {
    val manager = getCurrentTransaction()!!
    manager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCompletion(status: Int): Mono<Void> =
                Mono.fromRunnable {
                    callback.invoke(status)
                }
        },
    )
}

suspend fun executeAfterCommit(callback: () -> Unit) {
    val manager = getCurrentTransaction()!!
    manager.registerSynchronization(
        object : TransactionSynchronization {
            override fun afterCommit(): Mono<Void> =
                Mono.fromRunnable {
                    callback.invoke()
                }
        },
    )
}
