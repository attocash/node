package cash.atto.node.transaction

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.node.AccountHeightSearch
import cash.atto.commons.node.HeightSearch
import cash.atto.commons.spring.sortByHeight
import cash.atto.commons.toBigInteger
import cash.atto.node.account.AccountUpdated
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.take
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
    name = "Transactions",
    description =
        "Submit or query transaction blocks. After an interrupted publication request, inspect the original transaction hash " +
            "and account history before creating another payment. A timeout does not prove that the transaction failed. " +
            "See [payment failure recovery](https://atto.cash/docs/whitepaper/technical#payment-failure-recovery).",
)
class TransactionController(
    val repository: TransactionRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val transactionFlow = MutableSharedFlow<AttoTransaction>()

    @EventListener
    suspend fun process(accountUpdated: AccountUpdated) {
        transactionFlow.emit(accountUpdated.transaction.toAttoTransaction())
    }

    @Operation(
        summary = "Stream all latest transactions",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun stream(): Flow<AttoTransaction> =
        transactionFlow
            .onStart { logger.trace { "Started streaming latest transactions" } }
            .onCompletion { logger.trace { "Stopped streaming latest transactions" } }

    @GetMapping("/transactions/{hash}")
    @Operation(
        summary = "Get transaction",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun get(
        @PathVariable hash: AttoHash,
    ): ResponseEntity<AttoTransaction> {
        val transaction = repository.findById(hash)
        return ResponseEntity.ofNullable(transaction?.toAttoTransaction())
    }

    @GetMapping("/transactions/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream a single transaction",
        description =
            "Allows clients to track the confirmation of a transaction in real-time by streaming a single transaction by hash. " +
                "Useful when the transaction hash is shared ahead of time, like in payment protocols.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable hash: AttoHash,
    ): Flow<AttoTransaction> =
        transactionFlow
            .onSubscription {
                repository.findById(hash)?.let { emit(it.toAttoTransaction()) }
            }.filter { it.hash == hash }
            .onStart { logger.trace { "Started streaming $hash transaction" } }
            .onCompletion { logger.trace { "Stopped streaming $hash transaction" } }
            .take(1)

    @PostMapping("/accounts/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream transactions by account and height range",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun streamMultiple(
        @RequestBody search: HeightSearch,
    ): Flow<AttoTransaction> {
        val accountRanges = search.search

        if (accountRanges.any { it.fromHeight.value == 0UL }) {
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

                transactionFlow
                    .onSubscription {
                        emitAll(
                            repository
                                .findAsc(publicKey, fromHeight.value.toBigInteger(), toHeight.value.toBigInteger())
                                .map { it.toAttoTransaction() },
                        )
                    }.filter { it.block.publicKey == publicKey && it.height in fromHeight..toHeight }
                    .sortByHeight(fromHeight)
                    .take(expectedCount)
            }

        return merge(*accountFlows.toTypedArray())
            .onStart {
                logger.trace { "Started streaming transactions for ${accountRanges.size} accounts" }
            }.onCompletion {
                logger.trace { "Stopped streaming transactions for ${accountRanges.size} accounts" }
            }
    }

    @GetMapping("/accounts/{publicKey}/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream transactions by height",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1", required = false) fromHeight: AttoHeight,
        @RequestParam(required = false) toHeight: AttoHeight = AttoHeight.MAX,
    ): Flow<AttoTransaction> {
        val transactionSearch =
            AccountHeightSearch(
                AttoAddress(AttoAlgorithm.V1, publicKey),
                fromHeight,
                toHeight,
            )
        val search = HeightSearch(listOf(transactionSearch))
        return streamMultiple(search)
    }
}
