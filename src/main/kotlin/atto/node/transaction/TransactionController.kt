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
import io.swagger.v3.oas.annotations.Operation
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
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
    val node: atto.protocol.AttoNode,
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
    private val transactionPublisher = MutableSharedFlow<AttoTransaction>(100_000)
    private val transactionFlow = transactionPublisher.asSharedFlow()

    @EventListener
    suspend fun process(transactionSaved: TransactionSaved) {
        transactionPublisher.emit(transactionSaved.transaction.toAttoTransaction())
    }

    @GetMapping("/transactions/{hash}")
    @Operation(description = "Get transaction")
    suspend fun get(@PathVariable hash: AttoHash): ResponseEntity<AttoTransaction> {
        val transaction = repository.findById(hash)
        return ResponseEntity.ofNullable(transaction?.toAttoTransaction())
    }

    @GetMapping("/transactions/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE + "+json"])
    @Operation(description = "Stream a single transaction")
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
            .take(1)
            .map {
                Json.encodeToString(
                    AttoTransaction.serializer(),
                    it
                )
            } //https://github.com/spring-projects/spring-framework/issues/30398
    }

    @GetMapping("/accounts/{publicKey}/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE + "+json"])
    @Operation(description = "Stream transactions")
    suspend fun stream(@PathVariable publicKey: AttoPublicKey, @RequestParam fromHeight: ULong): Flow<String> {
        val transactionDatabaseFlow = repository.findAsc(publicKey, fromHeight)
            .map { it.toAttoTransaction() }

        val transactionFlow = transactionFlow
            .filter { it.block.publicKey == publicKey }

        return merge(transactionFlow, transactionDatabaseFlow)
            .sortByHeight(fromHeight)
            .onStart { logger.trace { "Started streaming transactions from $publicKey account and height equals or after $fromHeight" } }
            .map {
                Json.encodeToString(
                    AttoTransaction.serializer(),
                    it
                )
            } //https://github.com/spring-projects/spring-framework/issues/30398
    }

    @PostMapping("/transactions")
    @Operation(description = "Publish transaction")
    suspend fun publish(@RequestBody transaction: AttoTransaction, request: ServerHttpRequest) {
        if (!transaction.isValid(node.network)) {
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