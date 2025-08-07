package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.node.AccountSearch
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.forwardHeightBy
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

@RestController
@RequestMapping("/accounts")
@Tag(
    name = "Accounts",
    description =
        "Retrieve the latest state (snapshot) of an account. " +
            "Since transactions mutate accounts, this reflects the result of all previous operations.",
)
class AccountController(
    val node: AttoNode,
    val eventPublisher: EventPublisher,
    val repository: AccountRepository,
    val crudRepository: AccountCrudRepository,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val accountFlow = MutableSharedFlow<AttoAccount>()

    @EventListener
    suspend fun process(accountUpdated: AccountUpdated) {
        accountFlow.emit(accountUpdated.updatedAccount.toAttoAccount())
    }

    @GetMapping("/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream all latest accounts",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccountExample::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(): Flow<AttoAccount> =
        accountFlow
            .onStart { logger.trace { "Started streaming latest transactions" } }
            .onCompletion { logger.trace { "Stopped streaming latest transactions" } }

    @OptIn(ExperimentalCoroutinesApi::class)
    @PostMapping
    @Operation(
        summary = "Get accounts for given addresses",
        requestBody =
            io.swagger.v3.oas.annotations.parameters.RequestBody(
                content = [
                    Content(
                        schema = Schema(implementation = AccountSearchSample::class),
                    ),
                ],
            ),
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccountExample::class),
                    ),
                ],
            ),
        ],
    )
    fun get(
        @RequestBody search: AccountSearch,
    ): Flow<AttoAccount> {
        val publicKeys = search.addresses.map { it.publicKey }

        return publicKeys
            .chunked(1_000)
            .asFlow()
            .flatMapConcat { batch ->
                repository
                    .findAllById(batch)
                    .map { it.toAttoAccount() }
            }
    }

    @GetMapping("/{publicKey}")
    @Operation(
        summary = "Get account",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccountExample::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun get(
        @PathVariable publicKey: AttoPublicKey,
    ): ResponseEntity<AttoAccount> {
        val account = get(AccountSearch(setOf(AttoAddress(AttoAlgorithm.V1, publicKey)))).firstOrNull()
        return ResponseEntity.ofNullable(account)
    }

    @PostMapping("/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream all accounts for the given addresses",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccountExample::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(search: AccountSearch): Flow<AttoAccount> {
        val addresses = search.addresses.toSet()

        val accountDatabaseFlow =
            flow {
                val publicKeys =
                    search.addresses
                        .asSequence()
                        .map { it.publicKey }
                        .toSet()

                publicKeys.chunked(1000).forEach { chunk ->
                    repository
                        .findAllById(chunk)
                        .map { it.toAttoAccount() }
                        .collect { emit(it) }
                }
            }

        val accountUpdateFlow =
            accountFlow
                .filter { addresses.contains(AttoAddress(it.algorithm, it.publicKey)) }

        return merge(accountDatabaseFlow, accountUpdateFlow)
            .forwardHeightBy { it.address }
            .onStart { logger.trace { "Started streaming accounts for $addresses" } }
            .onCompletion { logger.trace { "Stopped streaming accounts for $addresses" } }
    }

    @GetMapping("/{publicKey}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream account",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccountExample::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
    ): Flow<AttoAccount> {
        val address = AttoAddress(AttoAlgorithm.V1, publicKey)
        return stream(AccountSearch(setOf(address)))
    }


    @GetMapping("/top")
    suspend fun getTop100(): List<AttoAccount> {
        return crudRepository.getTop100().map { it.toAttoAccount() }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun clear() {
        accountFlow.resetReplayCache()
    }

    @Schema(name = "AccountSearch", description = "List of addresses")
    data class AccountSearchSample(
        @field:ArraySchema(
            schema =
                Schema(
                    description = "Address of the account (without atto://)",
                    example = "adwmbykpqs3mgbqogizzwm6arokkcmuxium7rbh343drwd2q5om6vj3jrfiyk",
                ),
        )
        val addresses: Collection<String>,
    )

    @Schema(name = "AttoAccount", description = "Represents an Atto account")
    internal data class AttoAccountExample(
        @param:Schema(
            description = "The public key of the account",
            example = "45B3B58C26181580EEAFC1791046D54EEC2854BF550A211E2362761077D6590C",
        )
        val publicKey: String,
        @param:Schema(description = "Network type", example = "LIVE")
        val network: AttoNetwork,
        @param:Schema(description = "Version", example = "0")
        val version: Int,
        @param:Schema(description = "Type", example = "V1")
        val algorithm: AttoAlgorithm,
        @param:Schema(description = "Height", example = "1")
        val height: BigDecimal,
        @param:Schema(description = "Balance", example = "180000000000")
        val balance: BigDecimal,
        @param:Schema(description = "Last transaction hash", example = "70F9406609BCB2E3E18F22BD0839C95E5540E95489DC6F24DBF6A1F7CFD83A92")
        val lastTransactionHash: String,
        @param:Schema(description = "Timestamp of the last transaction", example = "1705517157478")
        val lastTransactionTimestamp: Long,
        @param:Schema(description = "Representative algorithm", example = "V1")
        val representativeAlgorithm: String,
        @param:Schema(
            description = "Public key of the representative",
            example = "99E439410A4DDD2A3A8D0B667C7A090286B8553378CF3C7AA806C3E60B6C4CBE",
        )
        val representativePublicKey: String,
    )
}
