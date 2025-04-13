package cash.atto.node.account.entry

import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoBlockType
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.toAttoHeight
import cash.atto.node.CacheSupport
import cash.atto.node.NotVoterCondition
import cash.atto.node.sortByHeight
import cash.atto.node.toBigInteger
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.takeWhile
import org.springframework.context.annotation.Conditional
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

@RestController
@RequestMapping
@Conditional(NotVoterCondition::class)
@Tag(
    name = "Account Entries",
    description = "A user-friendly view of account activity. Recommended for displaying transaction history in UIs.",
)
class AccountEntryController(
    val repository: AccountEntryRepository,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val entryFlow = MutableSharedFlow<AttoAccountEntry>()

    @EventListener
    suspend fun process(event: AccountEntrySaved) {
        entryFlow.emit(event.entry.toAtto())
    }

    @GetMapping("/accounts/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream all latest account entries",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoAccountEntrySample::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(): Flow<AttoAccountEntry> =
        entryFlow
            .onStart { logger.trace { "Started streaming latest account entries" } }
            .onCompletion { logger.trace { "Stopped streaming latest account entries" } }

    @GetMapping("/accounts/entries/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream a single account entry",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoAccountEntrySample::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable hash: AttoHash,
    ): Flow<AttoAccountEntry> {
        val entryDatabaseFlow: Flow<AttoAccountEntry> =
            flow {
                val transaction = repository.findById(hash)
                if (transaction != null) {
                    emit(transaction.toAtto())
                }
            }

        val entryFlow =
            entryFlow
                .filter { it.hash == hash }

        return merge(entryFlow, entryDatabaseFlow)
            .onStart { logger.trace { "Started streaming $hash account entry" } }
            .onCompletion { logger.trace { "Stopped streaming $hash account entry" } }
            .take(1)
    }

    @GetMapping("/accounts/{publicKey}/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream account entries by height",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoAccountEntrySample::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1", required = false) fromHeight: String,
        @RequestParam(defaultValue = "${ULong.MAX_VALUE}", required = false) toHeight: String,
    ): Flow<AttoAccountEntry> {
        val from = fromHeight.toULong()
        val to = toHeight.toULong()

        if (from == 0UL) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "fromHeight can't be zero")
        }

        if (from > to) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "toHeight should be higher or equals fromHeight")
        }

        val databaseFlow =
            repository
                .findAsc(publicKey, from.toBigInteger(), to.toBigInteger())
                .map { it.toAtto() }

        val entryFlow =
            entryFlow
                .filter { it.publicKey == publicKey }
                .filter { it.height in from.toAttoHeight()..to.toAttoHeight() }

        return merge(entryFlow, databaseFlow)
            .sortByHeight(from.toAttoHeight())
            .takeWhile { it.height <= to.toAttoHeight() }
            .onStart {
                logger.trace { "Started streaming entries from $publicKey account and height between $fromHeight and $toHeight" }
            }.onCompletion {
                logger.trace { "Stopped streaming entries from $publicKey account and height between $fromHeight and $toHeight" }
            }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun clear() {
        entryFlow.resetReplayCache()
    }

    @Schema(
        name = "AttoAccountEntry",
        description = "Represents an account chain entry",
    )
    internal data class AttoAccountEntrySample(
        @Schema(description = "Unique hash of the block", example = "68BA42CDD87328380BE32D5AA6DBB86E905B50273D37AF1DE12F47B83A001154")
        val hash: String,
        @Schema(description = "Block algorithm", example = "V1")
        val algorithm: AttoAlgorithm,
        @Schema(description = "Public key of the account", example = "FD595851104FDDB2FEBF3739C8006C8AAE9B8A2B1BC390D5FDF07EBDD8583FA1")
        val publicKey: String,
        @Schema(description = "Block height", example = "0")
        val height: BigDecimal,
        @Schema(description = "Type of block in the account chain", example = "RECEIVE")
        val blockType: AttoBlockType,
        @Schema(description = "Algorithm of the subject involved in the transaction", example = "V1")
        val subjectAlgorithm: AttoAlgorithm,
        @Schema(
            description = "Public key of the subject involved in the transaction",
            example = "2EB21717813E7A0E0A7E308B8E2FD8A051F8724F5C5F0047E92E19310C582E3A",
        )
        val subjectPublicKey: String,
        @Schema(description = "Balance before this block", example = "0")
        val previousBalance: BigDecimal,
        @Schema(description = "Balance after this block", example = "100")
        val balance: BigDecimal,
        @Schema(description = "Timestamp of the block", example = "1704616009211")
        val timestamp: Long,
    )
}
