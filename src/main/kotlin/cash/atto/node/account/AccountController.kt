package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoPublicKey
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
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping
@Tag(
    name = "Accounts",
    description =
        "Retrieve the latest state (snapshot) of an account. " +
            "Since transactions mutate accounts, this reflects the result of all previous operations.",
)
class AccountController(
    private val queries: AccountQueries,
) {
    @Operation(
        operationId = "streamAllAccounts",
        summary = "Stream all latest accounts",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccount::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/v2/accounts/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(): Flow<AttoAccount> = queries.stream()

    @Operation(operationId = "getAccountsByAddresses", summary = "Get accounts for given addresses")
    @PostMapping("/v2/accounts", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun get(
        @RequestBody search: AccountSearch,
    ): Flow<AttoAccount> = queries.get(search)

    @Operation(operationId = "getAccountByAddress", summary = "Get account by address")
    @GetMapping("/v2/accounts/{address}")
    suspend fun get(
        @PathVariable address: AttoAddress,
    ): ResponseEntity<AttoAccount> = get(address.publicKey)

    suspend fun get(
        @PathVariable publicKey: AttoPublicKey,
    ): ResponseEntity<AttoAccount> = ResponseEntity.ofNullable(queries.get(publicKey))

    @Operation(
        operationId = "streamAccountsByAddresses",
        summary = "Stream all accounts for the given addresses",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccount::class),
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/v2/accounts/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(
        @RequestBody search: AccountSearch,
    ): Flow<AttoAccount> = queries.stream(search)

    @Operation(
        operationId = "streamAccountByAddress",
        summary = "Stream account by address",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(schema = Schema(implementation = AttoAccount::class))],
            ),
        ],
    )
    @GetMapping("/v2/accounts/{address}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(
        @PathVariable address: AttoAddress,
    ): Flow<AttoAccount> = stream(address.publicKey)

    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
    ): Flow<AttoAccount> = queries.stream(publicKey)

    suspend fun getTop100(): List<AttoAccount> = queries.getTop100()

    @Hidden
    @Operation(
        operationId = "streamAllLegacyAccounts",
        summary = "Stream all latest accounts",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyAccountResponse::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/accounts/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(): Flow<LegacyAccountResponse> = stream().map { it.toLegacyResponse() }

    @Operation(
        operationId = "getLegacyAccountsByAddresses",
        summary = "Get accounts for given addresses",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyAccountResponse::class),
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/accounts")
    fun legacyGet(
        @RequestBody search: AccountSearch,
    ): Flow<LegacyAccountResponse> = get(search).map { it.toLegacyResponse() }

    @Hidden
    @Operation(operationId = "getLegacyAccountByPublicKey")
    @GetMapping("/accounts/{publicKey}")
    suspend fun legacyGet(
        @PathVariable publicKey: AttoPublicKey,
    ): ResponseEntity<LegacyAccountResponse> = get(publicKey).let { ResponseEntity(it.body?.toLegacyResponse(), it.headers, it.statusCode) }

    @Hidden
    @Operation(
        operationId = "streamLegacyAccountsByAddresses",
        summary = "Stream all accounts for the given addresses",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = LegacyAccountResponse::class),
                    ),
                ],
            ),
        ],
    )
    @PostMapping("/accounts/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(
        @RequestBody search: AccountSearch,
    ): Flow<LegacyAccountResponse> = stream(search).map { it.toLegacyResponse() }

    @Hidden
    @Operation(operationId = "streamLegacyAccountByPublicKey")
    @GetMapping("/accounts/{publicKey}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun legacyStream(
        @PathVariable publicKey: AttoPublicKey,
    ): Flow<LegacyAccountResponse> = stream(publicKey).map { it.toLegacyResponse() }

    @Hidden
    @Operation(operationId = "getLegacyTop100Accounts")
    @GetMapping("/accounts/top")
    suspend fun legacyGetTop100(): List<LegacyAccountResponse> = getTop100().map { it.toLegacyResponse() }
}
