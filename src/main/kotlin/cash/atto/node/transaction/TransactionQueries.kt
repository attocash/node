package cash.atto.node.transaction

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.node.AccountHeightSearch
import cash.atto.commons.node.HeightSearch
import cash.atto.commons.spring.sortByHeight
import cash.atto.commons.toBigInteger
import cash.atto.node.account.AccountUpdated
import cash.atto.node.account.validateRanges
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class TransactionQueries(
    private val repository: TransactionRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val transactionFlow = MutableSharedFlow<AttoTransaction>()

    @EventListener
    suspend fun process(accountUpdated: AccountUpdated) {
        transactionFlow.emit(accountUpdated.transaction.toAttoTransaction())
    }

    suspend fun stream(): Flow<AttoTransaction> =
        transactionFlow
            .onStart { logger.trace { "Started streaming latest transactions" } }
            .onCompletion { logger.trace { "Stopped streaming latest transactions" } }

    suspend fun get(hash: AttoHash): AttoTransaction? {
        val transaction = repository.findById(hash)
        return transaction?.toAttoTransaction()
    }

    suspend fun stream(hash: AttoHash): Flow<AttoTransaction> =
        transactionFlow
            .onSubscription {
                repository.findById(hash)?.let { emit(it.toAttoTransaction()) }
            }.filter { it.hash == hash }
            .onStart { logger.trace { "Started streaming $hash transaction" } }
            .onCompletion { logger.trace { "Stopped streaming $hash transaction" } }
            .take(1)

    suspend fun streamMultiple(search: HeightSearch): Flow<AttoTransaction> {
        val accountRanges = search.search

        search.validateRanges()

        val accountFlows =
            accountRanges.map { range ->
                val publicKey = range.address.publicKey
                val fromHeight = range.fromHeight
                val toHeight = range.toHeight
                val expectedCount =
                    (range.toHeight - range.fromHeight + 1UL).let {
                        if (it.value > Int.MAX_VALUE.toUInt()) Int.MAX_VALUE else it.value.toInt()
                    }

                transactionFlow
                    .onSubscription {
                        emitAll(
                            repository
                                .findAsc(publicKey, fromHeight.value.toBigInteger(), toHeight.value.toBigInteger())
                                .map { it.toAttoTransaction() },
                        )
                    }.filter { it.block.address.publicKey == publicKey && it.height in fromHeight..toHeight }
                    .sortByHeight(fromHeight)
                    .take(expectedCount)
            }

        return merge(*accountFlows.toTypedArray())
            .onStart {
                logger.trace { "Started streaming transactions for ${accountRanges.size} accounts" }
            }.onCompletion {
                logger.trace { "Stopped streaming transactions for ${accountRanges.size} accounts" }
            }
    }

    suspend fun stream(
        address: AttoAddress,
        fromHeight: AttoHeight,
        toHeight: AttoHeight = AttoHeight.MAX,
    ): Flow<AttoTransaction> = stream(address.publicKey, fromHeight, toHeight)

    suspend fun stream(
        publicKey: AttoPublicKey,
        fromHeight: AttoHeight,
        toHeight: AttoHeight = AttoHeight.MAX,
    ): Flow<AttoTransaction> {
        val transactionSearch =
            AccountHeightSearch(
                AttoAddress(AttoAlgorithm.V1, publicKey),
                fromHeight,
                toHeight,
            )
        val search = HeightSearch(listOf(transactionSearch))
        return streamMultiple(search)
    }
}
