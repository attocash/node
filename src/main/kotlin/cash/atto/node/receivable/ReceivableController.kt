package cash.atto.node.receivable

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
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
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

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
    @GetMapping("/accounts/{publicKey}/receivables/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream all receivables",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoReceivableExample::class),
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
            }.onStart { logger.trace { "Started streaming receivable for $publicKey account" } }
            .onCompletion { logger.trace { "Stopped streaming transactions for $publicKey account" } }
    }

    @Schema(name = "AttoReceivable", description = "Represents an Atto transaction")
    internal data class AttoReceivableExample(
        @Schema(description = "Transaction hash", example = "0AF0F63BFE4DBC588F95FC3B154DE848AA9A5DD5604BAC99AE9E21C5EA8B4F64")
        val hash: String,
        @Schema(description = "Version", example = "0")
        val version: Int,
        @Schema(description = "Algorithm", example = "V1")
        val algorithm: AttoAlgorithm,
        @Schema(description = "Public key of the sender", example = "53F1A85D25EDA5021C01A77A2B1BA99CEF9DD5FD912D7465B8B652FDEDB6A4F8")
        val publicKey: String,
        @Schema(description = "Timestamp of the send transaction", example = "1705517157478")
        val timestamp: Instant,
        @Schema(description = "Algorithm used by the receiver", example = "V1")
        val receiverAlgorithm: AttoAlgorithm,
        @Schema(description = "Public key of the receiver", example = "0C400961629D759176F009249A33899440900ABCE275F6C5C01C6F7F37A2C59A")
        val receiverPublicKey: String,
        @Schema(description = "Amount", example = "18000000000000000000")
        val amount: BigDecimal,
    )
}
