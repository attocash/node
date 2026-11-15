package cash.atto.node.receivable

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.node.AccountSearch
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
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
    private val queries: ReceivableQueries,
) {
    @Operation(
        operationId = "streamReceivablesByAddresses",
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
    @PostMapping("/v2/accounts/receivables/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(
        @RequestBody search: AccountSearch,
        @RequestParam(defaultValue = "1") minAmount: AttoAmount,
    ): Flow<AttoReceivable> = queries.stream(search, minAmount)

    @Operation(
        operationId = "streamReceivablesByAddress",
        summary = "Stream receivables by address",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(schema = Schema(implementation = AttoReceivable::class))],
            ),
        ],
    )
    @GetMapping("/v2/accounts/{address}/receivables/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(
        @PathVariable address: AttoAddress,
        @RequestParam(defaultValue = "1") minAmount: AttoAmount,
    ): Flow<AttoReceivable> = stream(address.publicKey, minAmount)

    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1") minAmount: AttoAmount,
    ): Flow<AttoReceivable> = queries.stream(publicKey, minAmount)

    @Hidden
    @Operation(
        operationId = "streamLegacyReceivablesByAddresses",
        summary = "Stream all receivables for multiple addresses",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyReceivableResponse::class),
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/accounts/receivables/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(
        @RequestBody search: AccountSearch,
        @RequestParam(defaultValue = "1") minAmount: AttoAmount,
    ): Flow<LegacyReceivableResponse> = stream(search, minAmount).map { it.toLegacyResponse() }

    @Hidden
    @Operation(operationId = "streamLegacyReceivablesByPublicKey")
    @GetMapping("/accounts/{publicKey}/receivables/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1") minAmount: AttoAmount,
    ): Flow<LegacyReceivableResponse> = stream(publicKey, minAmount).map { it.toLegacyResponse() }
}
