package cash.atto.node.receivable

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.node.AccountSearch
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.produceIn
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class ReceivableQueries(
    private val repository: ReceivableRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val receivableFlow = MutableSharedFlow<Receivable>()

    @EventListener
    suspend fun process(receivableSaved: ReceivableSaved) {
        receivableFlow.emit(receivableSaved.receivable)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun stream(
        search: AccountSearch,
        minAmount: AttoAmount,
    ): Flow<AttoReceivable> {
        val publicKeys = search.addresses.map { it.publicKey }.toSet()
        val databaseReceivables =
            publicKeys
                .chunked(1000)
                .asFlow()
                .flatMapConcat { repository.findAllDesc(it, minAmount) }

        val receivables =
            flow {
                coroutineScope {
                    val subscribed = CompletableDeferred<Unit>()
                    val liveReceivables =
                        receivableFlow
                            .onSubscription { subscribed.complete(Unit) }
                            .filter { it.receiverPublicKey in publicKeys }
                            .filter { it.amount >= minAmount }
                            .produceIn(this)

                    try {
                        val databaseHashes = HashSet<AttoHash>()

                        // Wait for the live subscription before scanning so concurrent commits cannot fall into a gap.
                        subscribed.await()
                        emitAll(databaseReceivables.onEach { databaseHashes.add(it.hash) })

                        // Retain snapshot hashes to suppress delayed notifications without caching live history.
                        emitAll(liveReceivables.consumeAsFlow().filterNot { it.hash in databaseHashes })
                    } finally {
                        liveReceivables.cancel()
                    }
                }
            }

        return receivables
            .map { it.toAttoReceivable() }
            .flatMapMerge { receivable ->
                val duration = receivable.timestamp - AttoInstant.now()

                if (duration.isPositive()) {
                    flow {
                        delay(duration)
                        emit(receivable)
                    }
                } else {
                    flowOf(receivable)
                }
            }.onStart { logger.trace { "Started streaming receivables for addresses $search" } }
            .onCompletion { logger.trace { "Stopped streaming receivables for addresses $search" } }
    }

    suspend fun stream(
        address: AttoAddress,
        minAmount: AttoAmount,
    ): Flow<AttoReceivable> = stream(address.publicKey, minAmount)

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun stream(
        publicKey: AttoPublicKey,
        minAmount: AttoAmount,
    ): Flow<AttoReceivable> {
        val address = AttoAddress(AttoAlgorithm.V1, publicKey)
        return stream(AccountSearch(listOf(address)), minAmount)
    }
}
