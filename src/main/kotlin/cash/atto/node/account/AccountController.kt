package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoPublicKey
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.NotVoterCondition
import cash.atto.node.forwardHeight
import cash.atto.protocol.AttoNode
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import org.springframework.context.annotation.Conditional
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/accounts")
@Conditional(NotVoterCondition::class)
class AccountController(
    val node: AttoNode,
    val eventPublisher: EventPublisher,
    val repository: AccountRepository,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    /**
     * There's a small chance that during subscription a client may miss the entry in the database and in the transaction
     * flow.
     *
     * The replay was added to workaround that. In any case, it's recommended to subscribe before publish transactions
     *
     */
    private val accountFlow = MutableSharedFlow<AttoAccount>(100_000)

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
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
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

    @GetMapping("/{publicKey}")
    @Operation(description = "Get account")
    suspend fun get(
        @PathVariable publicKey: AttoPublicKey,
    ): ResponseEntity<AttoAccount> {
        val account = repository.findById(publicKey)
        return ResponseEntity.ofNullable(account?.toAttoAccount())
    }

    @GetMapping("/{publicKey}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream account",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoAccount::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
    ): Flow<AttoAccount> {
        val accountDatabaseFlow =
            flow {
                repository.findById(publicKey)?.let {
                    emit(it.toAttoAccount())
                }
            }
        val accountFlow =
            accountFlow
                .filter { it.publicKey == publicKey }

        return merge(accountFlow, accountDatabaseFlow)
            .forwardHeight()
            .onStart { logger.trace { "Started streaming $publicKey account" } }
            .onCompletion { logger.trace { "Stopped streaming $publicKey account" } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun clear() {
        accountFlow.resetReplayCache()
    }
}
