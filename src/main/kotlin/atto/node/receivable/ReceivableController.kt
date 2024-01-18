package atto.node.receivable


import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.serialiazers.json.AttoJson
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
class ReceivableController(
    val repository: ReceivableRepository
) {
    private val logger = KotlinLogging.logger {}


    private val receivableFlow = MutableSharedFlow<Receivable>()

    @EventListener
    suspend fun process(transactionSaved: ReceivableSaved) {
        receivableFlow.emit(transactionSaved.receivable)
    }

    @GetMapping("/accounts/{publicKey}/receivables/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE + "+json"])
    @Operation(
        summary = "Stream all receivables",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(
                    mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                    schema = Schema(implementation = Receivable::class)
                )]
            )
        ]
    )

    suspend fun stream(@PathVariable publicKey: AttoPublicKey): Flow<String> {
        val receivableDatabaseFlow = repository.findAsc(publicKey)

        val receivableFlow = receivableFlow
            .filter { it.receiverPublicKey == publicKey }

        val knownHashes = HashSet<AttoHash>()
        return merge(receivableDatabaseFlow, receivableFlow)
            .filter { knownHashes.add(it.hash) }
            .map { it.toAttoReceivable() }
            .onStart { logger.trace { "Started streaming receivable for $publicKey account" } }
            .onCompletion { logger.trace { "Stopped streaming transactions for $publicKey account" } }
            .map {
                AttoJson.encodeToString(
                    AttoReceivable.serializer(),
                    it
                )
            } //https://github.com/spring-projects/spring-framework/issues/30398
    }
}