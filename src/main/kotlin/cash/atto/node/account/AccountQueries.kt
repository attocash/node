package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.node.AccountSearch
import cash.atto.commons.spring.forwardHeightBy
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class AccountQueries(
    private val repository: AccountRepository,
    private val crudRepository: AccountCrudRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val accountFlow = MutableSharedFlow<AttoAccount>()

    @EventListener
    suspend fun process(accountUpdated: AccountUpdated) {
        accountFlow.emit(accountUpdated.updatedAccount.toAttoAccount())
    }

    suspend fun stream(): Flow<AttoAccount> =
        accountFlow
            .onStart { logger.trace { "Started streaming latest transactions" } }
            .onCompletion { logger.trace { "Stopped streaming latest transactions" } }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun get(search: AccountSearch): Flow<AttoAccount> {
        val publicKeys = search.addresses.map { it.publicKey }

        return publicKeys
            .chunked(1_000)
            .asFlow()
            .flatMapConcat { batch ->
                repository
                    .findAllById(batch)
                    .map { it.toAttoAccount() }
            }
    }

    suspend fun get(address: AttoAddress): AttoAccount? = get(address.publicKey)

    suspend fun get(publicKey: AttoPublicKey): AttoAccount? {
        val account = repository.findById(publicKey)?.toAttoAccount()
        return account
    }

    suspend fun stream(search: AccountSearch): Flow<AttoAccount> {
        val addresses = search.addresses.toSet()

        val accountDatabaseFlow =
            flow {
                val publicKeys =
                    search.addresses
                        .asSequence()
                        .map { it.publicKey }
                        .toSet()

                publicKeys.chunked(1000).forEach { chunk ->
                    repository
                        .findAllById(chunk)
                        .map { it.toAttoAccount() }
                        .collect { emit(it) }
                }
            }

        return accountFlow
            // Register before reading the snapshot so concurrent updates wait for the handoff.
            .onSubscription { emitAll(accountDatabaseFlow) }
            .filter { addresses.contains(it.address) }
            .forwardHeightBy { it.address }
            .onStart { logger.trace { "Started streaming accounts for $addresses" } }
            .onCompletion { logger.trace { "Stopped streaming accounts for $addresses" } }
    }

    suspend fun stream(address: AttoAddress): Flow<AttoAccount> = stream(address.publicKey)

    suspend fun stream(publicKey: AttoPublicKey): Flow<AttoAccount> {
        val address = AttoAddress(AttoAlgorithm.V1, publicKey)
        return stream(AccountSearch(setOf(address)))
    }

    suspend fun getTop100(): List<AttoAccount> = crudRepository.getTop100().map { it.toAttoAccount() }
}
