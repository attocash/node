package cash.atto.node.account.entry

import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
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
    name = "Account Entries",
    description = "A user-friendly view of account activity. Recommended for displaying transaction history in UIs.",
)
class AccountEntryController(
    private val queries: AccountEntryQueries,
) {
    @Operation(
        operationId = "streamAllAccountEntries",
        summary = "Stream all latest account entries",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccountEntry::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/v2/accounts/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(): Flow<AttoAccountEntry> = queries.stream()

    @Operation(
        operationId = "streamAccountEntryByHash",
        summary = "Stream a single account entry",
        description =
            "Allows clients to track the confirmation of a transaction in real-time by streaming a single account entry by hash. " +
                "Useful when the transaction hash is shared ahead of time, like in payment protocols.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccountEntry::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/v2/accounts/entries/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(
        @PathVariable hash: AttoHash,
    ): Flow<AttoAccountEntry> = queries.stream(hash)

    @Operation(
        operationId = "streamAccountEntriesByHeightRanges",
        summary = "Stream account entries by height range",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccountEntry::class),
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/v2/accounts/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun streamMultiple(
        @RequestBody search: HeightSearch,
    ): Flow<AttoAccountEntry> = queries.streamMultiple(search)

    @Operation(
        operationId = "streamAccountEntriesByAddress",
        summary = "Stream account entries by address and height range",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(schema = Schema(implementation = AttoAccountEntry::class))],
            ),
        ],
    )
    @GetMapping("/v2/accounts/{address}/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(
        @PathVariable address: AttoAddress,
        @RequestParam(defaultValue = "1", required = false) fromHeight: AttoHeight,
        @RequestParam(required = false) toHeight: AttoHeight?,
    ): Flow<AttoAccountEntry> = stream(address.publicKey, fromHeight, toHeight)

    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1", required = false) fromHeight: AttoHeight,
        @RequestParam(required = false) toHeight: AttoHeight?,
    ): Flow<AttoAccountEntry> = queries.stream(publicKey, fromHeight, toHeight)

    @Hidden
    @Operation(
        operationId = "streamAllLegacyAccountEntries",
        summary = "Stream all latest account entries",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyAccountEntryResponse::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/accounts/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(): Flow<LegacyAccountEntryResponse> = stream().map { it.toLegacyResponse() }

    @Hidden
    @Operation(
        operationId = "streamLegacyAccountEntryByHash",
        summary = "Stream a single account entry",
        description =
            "Allows clients to track the confirmation of a transaction in real-time by streaming a single account entry by hash. " +
                "Useful when the transaction hash is shared ahead of time, like in payment protocols.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyAccountEntryResponse::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/accounts/entries/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(
        @PathVariable hash: AttoHash,
    ): Flow<LegacyAccountEntryResponse> = stream(hash).map { it.toLegacyResponse() }

    @Hidden
    @Operation(
        operationId = "streamLegacyAccountEntriesByHeightRanges",
        summary = "Stream account entries by height range",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyAccountEntryResponse::class),
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/accounts/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStreamMultiple(
        @RequestBody search: HeightSearch,
    ): Flow<LegacyAccountEntryResponse> = streamMultiple(search).map { it.toLegacyResponse() }

    @Hidden
    @Operation(operationId = "streamLegacyAccountEntriesByPublicKey")
    @GetMapping("/accounts/{publicKey}/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1", required = false) fromHeight: AttoHeight,
        @RequestParam(required = false) toHeight: AttoHeight?,
    ): Flow<LegacyAccountEntryResponse> = stream(publicKey, fromHeight, toHeight).map { it.toLegacyResponse() }
}
