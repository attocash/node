package cash.atto.node.account.entry

import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoBlockType
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.node.AccountHeightSearch
import cash.atto.commons.node.HeightSearch
import cash.atto.commons.toAttoHeight
import cash.atto.node.CacheSupport
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
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

@RestController
@RequestMapping
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
        description =
            "Allows clients to track the confirmation of a transaction in real-time by streaming a single account entry by hash. " +
                "Useful when the transaction hash is shared ahead of time, like in payment protocols.",
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

    @Operation(
        summary = "Stream account entries by height range",
        requestBody =
            io.swagger.v3.oas.annotations.parameters.RequestBody(
                content = [
                    Content(
                        schema = Schema(implementation = HeightSearchSample::class),
                    ),
                ],
            ),
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
    @PostMapping("/accounts/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun streamMultiple(
        @RequestBody search: HeightSearch,
    ): Flow<AttoAccountEntry> {
        val accountRanges = search.search

        if (accountRanges.any { it.fromHeight == 0UL.toAttoHeight() }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "fromHeight can't be zero")
        }
        if (accountRanges.any { it.fromHeight > it.toHeight }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "toHeight must be greater or equal to fromHeight")
        }

        val accountFlows =
            accountRanges.map { range ->
                val publicKey = range.address.publicKey
                val fromHeight = range.fromHeight
                val toHeight = range.toHeight
                val expectedCount =
                    (range.toHeight - range.fromHeight + 1UL).let {
                        if (it.value > Int.MAX_VALUE.toUInt()) Int.MAX_VALUE else it.value.toInt()
                    }

                val dbFlow =
                    repository
                        .findAsc(publicKey, range.fromHeight.value.toBigInteger(), range.toHeight.value.toBigInteger())
                        .map { it.toAtto() }

                val liveFlow =
                    entryFlow
                        .filter { it.publicKey == publicKey }
                        .filter { it.height in fromHeight..toHeight }

                merge(liveFlow, dbFlow)
                    .sortByHeight(fromHeight)
                    .take(expectedCount)
            }

        return merge(*accountFlows.toTypedArray())
            .onStart {
                logger.trace { "Started streaming entries for ${accountRanges.size} accounts" }
            }.onCompletion {
                logger.trace { "Stopped streaming entries for ${accountRanges.size} accounts" }
            }
    }

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
    @GetMapping("/accounts/{publicKey}/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1", required = false) fromHeight: ULong,
        @RequestParam(required = false) toHeight: ULong?,
    ): Flow<AttoAccountEntry> {
        val transactionSearch =
            AccountHeightSearch(
                AttoAddress(AttoAlgorithm.V1, publicKey),
                fromHeight.toAttoHeight(),
                (toHeight ?: ULong.MAX_VALUE).toAttoHeight(),
            )
        val search = HeightSearch(listOf(transactionSearch))
        return streamMultiple(search)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun clear() {
        entryFlow.resetReplayCache()
    }

    @Schema(
        name = "AccountHeightSearch",
        description = "Account height range to be searched",
    )
    data class AccountHeightSearchSample(
        @param:Schema(
            description = "Address of the account (without atto://)",
            example = "adwmbykpqs3mgbqogizzwm6arokkcmuxium7rbh343drwd2q5om6vj3jrfiyk",
        )
        val address: String,
        @param:Schema(
            description = "From height (inclusive), normally last seen height + 1",
            example = "0",
        )
        val fromHeight: Long,
        @param:Schema(
            description = "To height (inclusive)",
            example = "0",
        )
        val toHeight: Long,
    )

    @Schema(
        name = "HeightSearch",
        description = "List of account heights to be searched",
    )
    data class HeightSearchSample(
        val search: Collection<AccountHeightSearchSample>,
    )

    @Schema(
        name = "AttoAccountEntry",
        description = "Represents an account chain entry",
    )
    internal data class AttoAccountEntrySample(
        @param:Schema(
            description = "Unique hash of the block",
            example = "68BA42CDD87328380BE32D5AA6DBB86E905B50273D37AF1DE12F47B83A001154",
        )
        val hash: String,
        @param:Schema(description = "Block algorithm", example = "V1")
        val algorithm: AttoAlgorithm,
        @param:Schema(
            description = "Public key of the account",
            example = "FD595851104FDDB2FEBF3739C8006C8AAE9B8A2B1BC390D5FDF07EBDD8583FA1",
        )
        val publicKey: String,
        @param:Schema(description = "Block height", example = "0")
        val height: BigDecimal,
        @param:Schema(description = "Type of block in the account chain", example = "RECEIVE")
        val blockType: AttoBlockType,
        @param:Schema(description = "Algorithm of the subject involved in the transaction", example = "V1")
        val subjectAlgorithm: AttoAlgorithm,
        @param:Schema(
            description = "Public key of the subject involved in the transaction",
            example = "2EB21717813E7A0E0A7E308B8E2FD8A051F8724F5C5F0047E92E19310C582E3A",
        )
        val subjectPublicKey: String,
        @param:Schema(description = "Balance before this block", example = "0")
        val previousBalance: BigDecimal,
        @param:Schema(description = "Balance after this block", example = "100")
        val balance: BigDecimal,
        @param:Schema(description = "Timestamp of the block", example = "1704616009211")
        val timestamp: Long,
    )
}
