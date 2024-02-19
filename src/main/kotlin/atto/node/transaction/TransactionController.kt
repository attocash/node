package atto.node.transaction

import atto.node.ApplicationProperties
import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.node.sortByHeight
import atto.protocol.transaction.AttoTransactionPush
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.serialiazers.json.AttoJson
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import kotlinx.coroutines.flow.*
import mu.KotlinLogging
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
class TransactionController(
    val applicationProperties: ApplicationProperties,
    val thisNode: atto.protocol.AttoNode,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
    val repository: TransactionRepository
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
                content = [Content(
                    mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                    schema = Schema(implementation = AttoTransaction::class)
                )]
            )
        ]
    )
    suspend fun stream(): Flow<String> {
        return transactionFlow
            .onStart { logger.trace { "Started streaming latest transactions" } }
            .onCompletion { logger.trace { "Stopped streaming latest transactions" } }
            .map {
                AttoJson.encodeToString(
                    AttoTransaction.serializer(),
                    it
                )
            } //https://github.com/spring-projects/spring-framework/issues/30398
    }

    @GetMapping("/transactions/{hash}")
    @Operation(description = "Get transaction")
    suspend fun get(@PathVariable hash: AttoHash): ResponseEntity<AttoTransaction> {
        val transaction = repository.findById(hash)
        return ResponseEntity.ofNullable(transaction?.toAttoTransaction())
    }

    @GetMapping("/transactions/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream a single transaction",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(
                    mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                    schema = Schema(implementation = AttoTransaction::class)
                )]
            )
        ]
    )
    suspend fun stream(@PathVariable hash: AttoHash): Flow<String> {
        val transactionDatabaseFlow: Flow<AttoTransaction> = flow {
            val transaction = repository.findById(hash)
            if (transaction != null) {
                emit(transaction.toAttoTransaction())
            }
        }

        val transactionFlow = transactionFlow
            .filter { it.hash == hash }

        return merge(transactionFlow, transactionDatabaseFlow)
            .onStart { logger.trace { "Started streaming $hash transaction" } }
            .onCompletion { logger.trace { "Stopped streaming $hash transaction" } }
            .take(1)
            .map {
                AttoJson.encodeToString(
                    AttoTransaction.serializer(),
                    it
                )
            } //https://github.com/spring-projects/spring-framework/issues/30398
    }

    @GetMapping("/accounts/{publicKey}/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(
        summary = "Stream transactions by height",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(
                    mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                    schema = Schema(implementation = AttoTransaction::class)
                )]
            )
        ]
    )

    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1") fromHeight: Long,
        @RequestParam(defaultValue = Long.MAX_VALUE.toString()) toHeight: Long
    ): Flow<String> {
        if (fromHeight.toULong() == 0UL) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "fromHeight can't be zero")
        }

        if (fromHeight > toHeight) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "toHeight should be higher or equals fromHeight")
        }

        val transactionDatabaseFlow = repository.findAsc(publicKey, fromHeight.toULong(), toHeight.toULong())
            .map { it.toAttoTransaction() }

        val transactionFlow = transactionFlow
            .filter { it.block.publicKey == publicKey }
            .takeWhile { fromHeight.toULong() <= it.height && it.height <= toHeight.toULong() }

        return merge(transactionFlow, transactionDatabaseFlow)
            .sortByHeight(fromHeight.toULong())
            .onStart { logger.trace { "Started streaming transactions from $publicKey account and height between ${fromHeight.toULong()} and ${toHeight.toULong()}" } }
            .onCompletion { logger.trace { "Stopped streaming transactions from $publicKey account and height between ${fromHeight.toULong()} and ${toHeight.toULong()}" } }
            .map {
                AttoJson.encodeToString(
                    AttoTransaction.serializer(),
                    it
                )
            } //https://github.com/spring-projects/spring-framework/issues/30398
    }

    @PostMapping("/transactions")
    @Operation(description = "Publish transaction")
    suspend fun publish(@RequestBody transaction: AttoTransaction, request: ServerHttpRequest) {
        if (!transaction.isValid(thisNode.network)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction");
        }

        val ips = request.headers[useXForwardedForKey] ?: listOf()
        val remoteAddress = request.remoteAddress!!

        val socketAddress = if (!applicationProperties.useXForwardedFor) {
            remoteAddress
        } else if (ips.isNotEmpty()) {
            InetSocketAddress.createUnresolved(ips[0], remoteAddress.port)
        } else {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "X-Forwarded-For is empty. Are you behind a proxy?"
            )
        }

        messagePublisher.publish(
            InboundNetworkMessage(
                thisNode.publicUri,
                socketAddress,
                AttoTransactionPush(transaction)
            )
        )
    }

    @PostMapping("/transactions/stream")
    @Operation(description = "Publish transaction and stream")
    suspend fun publishAndStream(
        @RequestBody transaction: AttoTransaction,
        request: ServerHttpRequest
    ): Flow<String> {
        return stream(transaction.hash)
            .onStart { publish(transaction, request) }
    }
}