package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.node.AccountSearch
import cash.atto.commons.spring.forwardHeightBy
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
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
                        schema = Schema(implementation = AttoAccount::class),
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
                        schema = Schema(implementation = AttoAccount::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @RequestBody search: AccountSearch,
    ): Flow<AttoAccount> {
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
                        schema = Schema(implementation = AttoAccount::class),
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
    @Hidden
    suspend fun getTop100(): List<AttoAccount> = crudRepository.getTop100().map { it.toAttoAccount() }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun clear() {
        accountFlow.resetReplayCache()
    }
}
