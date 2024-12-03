package cash.atto.node.account.entry

import cash.atto.commons.AttoAccountEntry
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

@RestController
@RequestMapping
@Conditional(NotVoterCondition::class)
class AccountEntryController(
    val repository: AccountEntryRepository,
) : CacheSupport {
    private val logger = KotlinLogging.logger {}

    private val entryFlow = MutableSharedFlow<AttoAccountEntry>(100_000)

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
                        schema = Schema(implementation = AttoAccountEntry::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(): Flow<AttoAccountEntry> {
        return entryFlow
            .onStart { logger.trace { "Started streaming latest account entries" } }
            .onCompletion { logger.trace { "Stopped streaming latest account entries" } }
    }

    @GetMapping("/accounts/entries/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream a single account entry",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
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

    @GetMapping("/accounts/{publicKey}/entries/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream account entries by height",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoAccountEntry::class),
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
                .takeWhile { it.height in from.toAttoHeight()..to.toAttoHeight() }

        return merge(entryFlow, databaseFlow)
            .sortByHeight(from.toAttoHeight())
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
}
