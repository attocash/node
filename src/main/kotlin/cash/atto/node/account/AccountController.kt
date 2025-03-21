package cash.atto.node.account

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoNetwork
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import org.springframework.context.annotation.Conditional
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal

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
                        schema = Schema(implementation = AttoAccountExample::class)
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
    @Operation(
        summary = "Get account",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoAccountExample::class)
                    ),
                ],
            ),
        ],
    )
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
                        schema = Schema(implementation = AttoAccountExample::class)
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


    @Schema(name = "AttoAccount", description = "Represents an Atto account")
    internal data class AttoAccountExample(
        @Schema(description = "The public key of the account", example = "45B3B58C26181580EEAFC1791046D54EEC2854BF550A211E2362761077D6590C")
        val publicKey: String,

        @Schema(description = "Network type", example = "LIVE")
        val network: AttoNetwork,

        @Schema(description = "Version", example = "0")
        val version: Int,

        @Schema(description = "Type", example = "V1")
        val algorithm: AttoAlgorithm,

        @Schema(description = "Height", example = "1")
        val height: BigDecimal,

        @Schema(description = "Balance", example = "180000000000")
        val balance: BigDecimal,

        @Schema(description = "Last transaction hash", example = "70F9406609BCB2E3E18F22BD0839C95E5540E95489DC6F24DBF6A1F7CFD83A92")
        val lastTransactionHash: String,

        @Schema(description = "Timestamp of the last transaction", example = "1705517157478")
        val lastTransactionTimestamp: Long,

        @Schema(description = "Representative algorithm", example = "V1")
        val representativeAlgorithm: String,

        @Schema(
            description = "Public key of the representative",
            example = "99E439410A4DDD2A3A8D0B667C7A090286B8553378CF3C7AA806C3E60B6C4CBE"
        )
        val representativePublicKey: String
    )

}
