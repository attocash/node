package cash.atto.node.transaction

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.node.HeightSearch
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
    name = "Transactions",
    description =
        "Submit or query transaction blocks. After an interrupted publication request, inspect the original transaction hash " +
            "and account history before creating another payment. A timeout does not prove that the transaction failed. " +
            "See [payment failure recovery](https://atto.cash/docs/whitepaper/technical#payment-failure-recovery).",
)
class TransactionController(
    private val queries: TransactionQueries,
) {
    @Operation(
        operationId = "streamAllTransactions",
        summary = "Stream all latest transactions",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/v2/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(): Flow<AttoTransaction> = queries.stream()

    suspend fun get(
        @PathVariable hash: AttoHash,
    ): ResponseEntity<AttoTransaction> = ResponseEntity.ofNullable(queries.get(hash))

    @Operation(
        operationId = "streamTransactionByHash",
        summary = "Stream a single transaction",
        description =
            "Allows clients to track the confirmation of a transaction in real-time by streaming a single transaction by hash. " +
                "Useful when the transaction hash is shared ahead of time, like in payment protocols.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/v2/transactions/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(
        @PathVariable hash: AttoHash,
    ): Flow<AttoTransaction> = queries.stream(hash)

    @Operation(
        operationId = "streamTransactionsByHeightRanges",
        summary = "Stream transactions by account and height range",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/v2/accounts/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun streamMultiple(
        @RequestBody search: HeightSearch,
    ): Flow<AttoTransaction> = queries.streamMultiple(search)

    @Operation(
        operationId = "streamTransactionsByAddress",
        summary = "Stream transactions by address and height range",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(schema = Schema(implementation = AttoTransaction::class))],
            ),
        ],
    )
    @GetMapping("/v2/accounts/{address}/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(
        @PathVariable address: AttoAddress,
        @RequestParam(defaultValue = "1", required = false) fromHeight: AttoHeight,
        @RequestParam(required = false) toHeight: AttoHeight = AttoHeight.MAX,
    ): Flow<AttoTransaction> = stream(address.publicKey, fromHeight, toHeight)

    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1", required = false) fromHeight: AttoHeight,
        @RequestParam(required = false) toHeight: AttoHeight = AttoHeight.MAX,
    ): Flow<AttoTransaction> = queries.stream(publicKey, fromHeight, toHeight)

    @Hidden
    @Operation(
        operationId = "streamAllLegacyTransactions",
        summary = "Stream all latest transactions",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyTransactionResponse::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(): Flow<LegacyTransactionResponse> = stream().map { it.toLegacyResponse() }

    @Operation(
        operationId = "getTransactionByHash",
        summary = "Get transaction",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyTransactionResponse::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/transactions/{hash}")
    suspend fun legacyGet(
        @PathVariable hash: AttoHash,
    ): ResponseEntity<LegacyTransactionResponse> = get(hash).let { ResponseEntity(it.body?.toLegacyResponse(), it.headers, it.statusCode) }

    @Hidden
    @Operation(
        operationId = "streamLegacyTransactionByHash",
        summary = "Stream a single transaction",
        description =
            "Allows clients to track the confirmation of a transaction in real-time by streaming a single transaction by hash. " +
                "Useful when the transaction hash is shared ahead of time, like in payment protocols.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyTransactionResponse::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/transactions/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(
        @PathVariable hash: AttoHash,
    ): Flow<LegacyTransactionResponse> = stream(hash).map { it.toLegacyResponse() }

    @Hidden
    @Operation(
        operationId = "streamLegacyTransactionsByHeightRanges",
        summary = "Stream transactions by account and height range",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyTransactionResponse::class),
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/accounts/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStreamMultiple(
        @RequestBody search: HeightSearch,
    ): Flow<LegacyTransactionResponse> = streamMultiple(search).map { it.toLegacyResponse() }

    @Hidden
    @Operation(operationId = "streamLegacyTransactionsByPublicKey")
    @GetMapping("/accounts/{publicKey}/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1", required = false) fromHeight: AttoHeight,
        @RequestParam(required = false) toHeight: AttoHeight = AttoHeight.MAX,
    ): Flow<LegacyTransactionResponse> = stream(publicKey, fromHeight, toHeight).map { it.toLegacyResponse() }
}
