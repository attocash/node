package cash.atto.node.receivable

import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.node.NotVoterCondition
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
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
import kotlinx.datetime.Clock
import org.springframework.context.annotation.Conditional
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
@Conditional(NotVoterCondition::class)
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
    @GetMapping("/accounts/{publicKey}/receivables/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream all receivables",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = Receivable::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1") minAmount: AttoAmount,
    ): Flow<AttoReceivable> {
        val receivableDatabaseFlow = repository.findAsc(publicKey, minAmount)

        val receivableFlow =
            receivableFlow
                .filter { it.receiverPublicKey == publicKey }
                .filter { it.amount >= minAmount }

        val knownHashes = HashSet<AttoHash>()
        return merge(receivableDatabaseFlow, receivableFlow)
            .filter { knownHashes.add(it.hash) }
            .map { it.toAttoReceivable() }
            .flatMapMerge {
                val duration = it.timestamp - Clock.System.now()

                if (duration.isPositive()) {
                    flow {
                        delay(duration)
                        emit(it)
                    }
                } else {
                    flowOf(it)
                }

            }
            .onStart { logger.trace { "Started streaming receivable for $publicKey account" } }
            .onCompletion { logger.trace { "Stopped streaming transactions for $publicKey account" } }
    }
}
