package cash.atto.node.account

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toAttoVersion
import cash.atto.node.AttoRepository
import com.github.benmanes.caffeine.cache.Caffeine
import org.springframework.context.annotation.Primary
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.reactive.TransactionSynchronization
import java.time.Duration
import java.time.Instant

interface AccountRepository : AttoRepository {
    suspend fun save(entity: Account): Account

    suspend fun findById(id: AttoPublicKey): Account?

    suspend fun findByAlgorithmAndPublicKey(
        algorithm: AttoAlgorithm,
        publicKey: AttoPublicKey,
    ): Account?
}

interface AccountCrudRepository :
    CoroutineCrudRepository<Account, AttoPublicKey>,
    AccountRepository {
    override suspend fun findByAlgorithmAndPublicKey(
        algorithm: AttoAlgorithm,
        publicKey: AttoPublicKey,
    ): Account?
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

    override suspend fun save(entity: Account): Account {
        val saved = accountCrudRepository.save(entity)

        cache[entity.publicKey] =
            saved.copy(
                persistedAt = entity.persistedAt ?: Instant.now(),
                updatedAt = Instant.now(),
            )

        executeAfterCompletion { status ->
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
                cache.remove(entity.publicKey)
            }
        }

        return saved
    }

    override suspend fun findById(id: AttoPublicKey): Account? = cache[id] ?: accountCrudRepository.findById(id)

    override suspend fun findByAlgorithmAndPublicKey(
        algorithm: AttoAlgorithm,
        publicKey: AttoPublicKey,
    ): Account? = accountCrudRepository.findByAlgorithmAndPublicKey(algorithm, publicKey)

    override suspend fun deleteAll() {
        accountCrudRepository.deleteAll()
    }
}

suspend fun AccountRepository.getByAlgorithmAndPublicKey(
    algorithm: AttoAlgorithm,
    publicKey: AttoPublicKey,
    network: AttoNetwork,
): Account {
    val account = findByAlgorithmAndPublicKey(algorithm, publicKey)
    if (account != null) {
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
