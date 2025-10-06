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
import cash.atto.node.ApplicationProperties
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.ElectionExpired
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.timeout
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress
import kotlin.time.Duration.Companion.seconds

@RestController
@RequestMapping
@Tag(
    name = "Transactions",
    description = "Submit or query raw transaction blocks. This endpoint handles the low-level building blocks of the ledger.",
)
class TransactionController(
    val applicationProperties: ApplicationProperties,
    val thisNode: AttoNode,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
    val repository: TransactionRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val useXForwardedForKey = "X-FORWARDED-FOR"

    private val transactionFlow = MutableSharedFlow<AttoTransaction>()
    private val rejectionFlow = MutableSharedFlow<Rejection>()

    @EventListener
    suspend fun process(accountUpdated: AccountUpdated) {
        transactionFlow.emit(accountUpdated.transaction.toAttoTransaction())
    }

    @EventListener
    suspend fun process(rejection: TransactionRejected) {
        rejectionFlow.emit(Rejection(rejection.transaction, rejection.reason.toString(), rejection.message))
    }

    @EventListener
    suspend fun process(expired: ElectionExpired) {
        rejectionFlow.emit(Rejection(expired.transaction, "ELECTION_EXPIRED", "Election took too long"))
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
    ): Flow<AttoTransaction> {
        val transactionDatabaseFlow: Flow<AttoTransaction> =
            flow {
                val transaction = repository.findById(hash)
                if (transaction != null) {
                    emit(transaction.toAttoTransaction())
                }
            }

        val transactionFlow =
            transactionFlow
                .filter { it.hash == hash }

        return merge(transactionFlow, transactionDatabaseFlow)
            .onStart { logger.trace { "Started streaming $hash transaction" } }
            .onCompletion { logger.trace { "Stopped streaming $hash transaction" } }
            .take(1)
    }

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

                val dbFlow =
                    repository
                        .findAsc(publicKey, range.fromHeight.value.toBigInteger(), range.toHeight.value.toBigInteger())
                        .map { it.toAttoTransaction() }

                val liveFlow =
                    transactionFlow
                        .filter { it.block.publicKey == publicKey }
                        .filter { it.height in fromHeight..toHeight }

                merge(liveFlow, dbFlow)
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

    @PostMapping("/transactions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Publish a transaction",
        responses = [
            ApiResponse(
                responseCode = "200",
            ),
        ],
    )
    suspend fun publish(
        @RequestBody transaction: AttoTransaction,
        request: ServerHttpRequest,
    ) {
        logger.debug { "Received $transaction" }

        if (!transaction.isValid()) {
            logger.debug { "Invalid! $transaction" }
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction")
        }

        if (transaction.block.network != thisNode.network) {
            logger.debug { "Invalid network! $transaction" }
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction network")
        }

        val ips = request.headers[useXForwardedForKey] ?: listOf()
        val remoteAddress = request.remoteAddress!!

        val socketAddress =
            if (!applicationProperties.useXForwardedFor) {
                remoteAddress
            } else if (ips.isNotEmpty()) {
                InetSocketAddress.createUnresolved(ips[0], remoteAddress.port)
            } else {
                logger.debug { "X-Forwarded-For header is empty. Are you sure you are behind a load balancer? $transaction" }
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "X-Forwarded-For header is empty. Are you sure you are behind a load balancer?",
                )
            }

        messagePublisher.publish(
            InboundNetworkMessage(
                MessageSource.REST,
                thisNode.publicUri,
                socketAddress,
                AttoTransactionPush(transaction),
            ),
        )
    }

    @OptIn(FlowPreview::class)
    @PostMapping(
        "/transactions/stream",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_NDJSON_VALUE],
    )
    @Operation(
        description = "Publish transaction and stream",
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
    suspend fun publishAndStream(
        @RequestBody transaction: AttoTransaction,
        request: ServerHttpRequest,
    ): Flow<AttoTransaction> {
        val rejectionErrorFlow =
            rejectionFlow
                .filter { it.transaction.hash == transaction.hash }
                .filter { it.reason != TransactionRejectionReason.ALREADY_CONFIRMED.name }
                .map<Rejection, AttoTransaction> { rejection ->
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Transaction rejected due to ${rejection.reason}: ${rejection.message}",
                    )
                }

        val successStream = stream(transaction.hash)

        return merge(rejectionErrorFlow, successStream)
            .onStart { publish(transaction, request) }
            .take(1)
            .timeout(40.seconds)
    }

    private class Rejection(
        val transaction: Transaction,
        val reason: String,
        val message: String,
    )
}
