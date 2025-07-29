package cash.atto.node.account

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toAttoVersion
import cash.atto.node.AttoRepository
import cash.atto.node.executeAfterCompletion
import cash.atto.node.getCurrentTransaction
import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import org.springframework.context.annotation.Primary
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionSynchronization
import java.time.Duration
import java.time.Instant

interface AccountRepository : AttoRepository {
    fun saveAll(entities: List<Account>): Flow<Account>

    suspend fun findById(id: AttoPublicKey): Account?

    fun findAllById(ids: Iterable<AttoPublicKey>): Flow<Account>
}

interface AccountCrudRepository :
    CoroutineCrudRepository<Account, AttoPublicKey>,
    AccountRepository {
    @Query("SELECT COALESCE(SUM(height), 0) FROM account")
    suspend fun sumHeight(): Long
}

@Primary
@Component
class AccountCachedRepository(
    private val accountCrudRepository: AccountCrudRepository,
) : AccountRepository {
    private val cache =
        Caffeine
            .newBuilder()
            .maximumSize(100_000)
            .expireAfterWrite(Duration.ofMinutes(10))
            .build<AttoPublicKey, Account>()
            .asMap()

    override fun saveAll(entities: List<Account>): Flow<Account> =
        accountCrudRepository.saveAll(entities).onEach { it ->
            val saved =
                it.copy(
                    persistedAt = it.persistedAt ?: Instant.now(),
                    updatedAt = Instant.now(),
                )

            getCurrentTransaction()!!.apply {
                this.unbindResourceIfPossible(saved.publicKey)
                this.bindResource(saved.publicKey, saved)
            }
            executeAfterCompletion { status ->
                if (status == TransactionSynchronization.STATUS_COMMITTED) {
                    cache[saved.publicKey] = saved
                }
            }
        }

    override suspend fun findById(id: AttoPublicKey): Account? = findAllById(listOf(id)).firstOrNull()

    override fun findAllById(ids: Iterable<AttoPublicKey>): Flow<Account> =
        flow {
            val missing = mutableListOf<AttoPublicKey>()

            for (id in ids) {
                val transactionalCached = getCurrentTransaction()?.getResource(id) as Account?
                if (transactionalCached != null) {
                    emit(transactionalCached)
                    continue
                }

                val cached = cache[id]
                if (cached != null) {
                    emit(cached)
                    continue
                }

                missing += id
            }

            if (missing.isNotEmpty()) {
                accountCrudRepository.findAllById(missing).collect { account ->
                    cache[account.publicKey] = account
                    emit(account)
                }
            }
        }

    override suspend fun deleteAll() {
        accountCrudRepository.deleteAll()
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
