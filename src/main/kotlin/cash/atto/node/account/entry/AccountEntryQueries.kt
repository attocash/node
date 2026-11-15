package cash.atto.node.account.entry

import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.node.AccountHeightSearch
import cash.atto.commons.node.HeightSearch
import cash.atto.commons.spring.sortByHeight
import cash.atto.commons.toBigInteger
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
class AccountEntryQueries(
    private val repository: AccountEntryRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val entryFlow = MutableSharedFlow<AttoAccountEntry>()

    @EventListener
    suspend fun process(event: AccountEntrySaved) {
        entryFlow.emit(event.entry.toAtto())
    }

    suspend fun stream(): Flow<AttoAccountEntry> =
        entryFlow
            .onStart { logger.trace { "Started streaming latest account entries" } }
            .onCompletion { logger.trace { "Stopped streaming latest account entries" } }

    suspend fun stream(hash: AttoHash): Flow<AttoAccountEntry> =
        entryFlow
            .onSubscription {
                repository.findById(hash)?.let { emit(it.toAtto()) }
            }.filter { it.hash == hash }
            .onStart { logger.trace { "Started streaming $hash account entry" } }
            .onCompletion { logger.trace { "Stopped streaming $hash account entry" } }
            .take(1)

    suspend fun streamMultiple(search: HeightSearch): Flow<AttoAccountEntry> {
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

                entryFlow
                    .onSubscription {
                        emitAll(
                            repository
                                .findAsc(publicKey, fromHeight.value.toBigInteger(), toHeight.value.toBigInteger())
                                .map { it.toAtto() },
                        )
                    }.filter { it.address.publicKey == publicKey && it.height in fromHeight..toHeight }
                    .sortByHeight(fromHeight)
                    .take(expectedCount)
            }

        return merge(*accountFlows.toTypedArray())
            .onStart {
                logger.trace { "Started streaming entries for ${accountRanges.size} accounts" }
            }.onCompletion {
                logger.trace { "Stopped streaming entries for ${accountRanges.size} accounts" }
            }
    }

    suspend fun stream(
        address: AttoAddress,
        fromHeight: AttoHeight,
        toHeight: AttoHeight?,
    ): Flow<AttoAccountEntry> = stream(address.publicKey, fromHeight, toHeight)

    suspend fun stream(
        publicKey: AttoPublicKey,
        fromHeight: AttoHeight,
        toHeight: AttoHeight?,
    ): Flow<AttoAccountEntry> {
        val transactionSearch =
            AccountHeightSearch(
                AttoAddress(AttoAlgorithm.V1, publicKey),
                fromHeight,
                (toHeight ?: AttoHeight.MAX),
            )
        val search = HeightSearch(listOf(transactionSearch))
        return streamMultiple(search)
    }
}
