package cash.atto.node.account.entry

import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.node.AccountHeightSearch
import cash.atto.commons.node.HeightSearch
import cash.atto.commons.spring.sortByHeight
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toBigInteger
import cash.atto.node.CacheSupport
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
                        schema = Schema(implementation = AttoAccountEntry::class),
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
                        schema = Schema(implementation = AttoAccountEntry::class),
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

    @PostMapping("/accounts/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
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

    @GetMapping("/accounts/{publicKey}/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream account entries by height",
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
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1", required = false) fromHeight: AttoHeight,
        @RequestParam(required = false) toHeight: AttoHeight?,
    ): Flow<AttoAccountEntry> {
        val transactionSearch =
            AccountHeightSearch(
                AttoAddress(AttoAlgorithm.V1, publicKey),
                fromHeight,
                (toHeight ?: AttoHeight.MAX),
            )
        val search = HeightSearch(listOf(transactionSearch))
        return streamMultiple(search)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun clear() {
        entryFlow.resetReplayCache()
    }
}
