package cash.atto.node.transaction

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.toAttoHeight
import cash.atto.node.ApplicationProperties
import cash.atto.node.EventPublisher
import cash.atto.node.NotVoterCondition
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.sortByHeight
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionPush
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
import org.springframework.context.annotation.Conditional
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress

@RestController
@RequestMapping
@Conditional(NotVoterCondition::class)
class TransactionController(
    val applicationProperties: ApplicationProperties,
    val thisNode: AttoNode,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
    val repository: TransactionRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val useXForwardedForKey = "X-FORWARDED-FOR"

    /**
     * There's a small chance that during subscription a client may miss the entry in the database and in the transaction
     * flow.
     *
     * The replay was added to workaround that. In any case, it's recommended to subscribe before publish transactions
     *
     */
    private val transactionFlow = MutableSharedFlow<AttoTransaction>(100_000)

    @EventListener
    suspend fun process(transactionSaved: TransactionSaved) {
        transactionFlow.emit(transactionSaved.transaction.toAttoTransaction())
    }

    @GetMapping("/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream all latest transactions",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(): Flow<AttoTransaction> {
        return transactionFlow
            .onStart { logger.trace { "Started streaming latest transactions" } }
            .onCompletion { logger.trace { "Stopped streaming latest transactions" } }
    }

    @GetMapping("/transactions/{hash}")
    @Operation(description = "Get transaction")
    suspend fun get(
        @PathVariable hash: AttoHash,
    ): ResponseEntity<AttoTransaction> {
        val transaction = repository.findById(hash)
        return ResponseEntity.ofNullable(transaction?.toAttoTransaction())
    }

    @GetMapping("/transactions/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream a single transaction",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
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

    @GetMapping("/accounts/{publicKey}/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream transactions by height",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1") fromHeight: AttoHeight,
        @RequestParam(defaultValue = "${ULong.MAX_VALUE}") toHeight: AttoHeight,
    ): Flow<AttoTransaction> {
        if (fromHeight == 0UL.toAttoHeight()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "fromHeight can't be zero")
        }

        if (fromHeight > toHeight) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "toHeight should be higher or equals fromHeight")
        }

        val transactionDatabaseFlow =
            repository
                .findAsc(publicKey, fromHeight, toHeight)
                .map { it.toAttoTransaction() }

        val transactionFlow =
            transactionFlow
                .filter { it.block.publicKey == publicKey }
                .takeWhile { it.height in fromHeight..toHeight }

        return merge(transactionFlow, transactionDatabaseFlow)
            .sortByHeight(fromHeight)
            .onStart {
                logger.trace { "Started streaming transactions from $publicKey account and height between $fromHeight and $fromHeight" }
            }.onCompletion {
                logger.trace { "Stopped streaming transactions from $publicKey account and height between $fromHeight and $fromHeight" }
            }
    }

    @PostMapping("/transactions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(description = "Publish transaction")
    suspend fun publish(
        @RequestBody transaction: AttoTransaction,
        request: ServerHttpRequest,
    ) {
        if (!transaction.isValid(thisNode.network)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction")
        }

        val ips = request.headers[useXForwardedForKey] ?: listOf()
        val remoteAddress = request.remoteAddress!!

        val socketAddress =
            if (!applicationProperties.useXForwardedFor) {
                remoteAddress
            } else if (ips.isNotEmpty()) {
                InetSocketAddress.createUnresolved(ips[0], remoteAddress.port)
            } else {
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

    @PostMapping(
        "/transactions/stream",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_NDJSON_VALUE],
    )
    @Operation(description = "Publish transaction and stream")
    suspend fun publishAndStream(
        @RequestBody transaction: AttoTransaction,
        request: ServerHttpRequest,
    ): Flow<AttoTransaction> =
        stream(transaction.hash)
            .onStart { publish(transaction, request) }
}
