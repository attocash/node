package cash.atto.node.transaction

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.toAttoHeight
import cash.atto.node.ApplicationProperties
import cash.atto.node.CacheSupport
import cash.atto.node.EventPublisher
import cash.atto.node.NotVoterCondition
import cash.atto.node.account.AccountUpdated
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.sortByHeight
import cash.atto.node.toBigInteger
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionPush
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

@RestController
@RequestMapping
@Conditional(NotVoterCondition::class)
class TransactionController(
    val applicationProperties: ApplicationProperties,
    val thisNode: AttoNode,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
    val repository: TransactionRepository,
) : CacheSupport {
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
    suspend fun process(accountUpdated: AccountUpdated) {
        transactionFlow.emit(accountUpdated.transaction.toAttoTransaction())
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
        @RequestParam(defaultValue = "1", required = false) fromHeight: String,
        @RequestParam(defaultValue = "${ULong.MAX_VALUE}", required = false) toHeight: String,
    ): Flow<AttoTransaction> {
        val from = fromHeight.toULong()
        val to = toHeight.toULong()

        if (from == 0UL) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "fromHeight can't be zero")
        }

        if (from > to) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "toHeight should be higher or equals fromHeight")
        }

        val transactionDatabaseFlow =
            repository
                .findAsc(publicKey, from.toBigInteger(), to.toBigInteger())
                .map { it.toAttoTransaction() }

        val transactionFlow =
            transactionFlow
                .filter { it.block.publicKey == publicKey }
                .filter { it.height in from.toAttoHeight()..to.toAttoHeight() }

        return merge(transactionFlow, transactionDatabaseFlow)
            .sortByHeight(from.toAttoHeight())
            .takeWhile { it.height <= to.toAttoHeight() }
            .onStart {
                logger.trace { "Started streaming transactions from $publicKey account and height between $fromHeight and $toHeight" }
            }.onCompletion {
                logger.trace { "Stopped streaming transactions from $publicKey account and height between $fromHeight and $toHeight" }
            }
    }

    @PostMapping("/transactions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(description = "Publish transaction")
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

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun clear() {
        transactionFlow.resetReplayCache()
    }
}
