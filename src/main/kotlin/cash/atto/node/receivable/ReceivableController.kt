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
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
@Tag(
    name = "Receivables",
    description =
        "Displays pending incoming funds. When someone sends a transaction, " +
            "it becomes a \"receivable\" until the recipient explicitly receives it.",
)
class ReceivableController(
    val repository: ReceivableRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val receivableFlow = MutableSharedFlow<Receivable>()

    @EventListener
    suspend fun process(receivableSaved: ReceivableSaved) {
        receivableFlow.emit(receivableSaved.receivable)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @PostMapping("/accounts/receivables/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream all receivables for multiple addresses",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoReceivable::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @RequestBody search: AccountSearch,
        @RequestParam(defaultValue = "1") minAmount: AttoAmount,
    ): Flow<AttoReceivable> {
        val publicKeys = search.addresses.map { it.publicKey }.toSet()

        val receivableDatabaseFlow =
            flow {
                publicKeys.chunked(1000).forEach { batch ->
                    repository
                        .findAllDesc(batch, minAmount)
                        .collect { emit(it) }
                }
            }

        val liveReceivableFlow =
            receivableFlow
                .filter { it.receiverPublicKey in publicKeys }
                .filter { it.amount >= minAmount }

        val knownHashes = HashSet<AttoHash>()

        return merge(receivableDatabaseFlow, liveReceivableFlow)
            .filter { knownHashes.add(it.hash) }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    @GetMapping("/accounts/{publicKey}/receivables/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream all receivables",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoReceivable::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1") minAmount: AttoAmount,
    ): Flow<AttoReceivable> {
        val address = AttoAddress(AttoAlgorithm.V1, publicKey)
        return stream(AccountSearch(listOf(address)), minAmount)
    }
}
