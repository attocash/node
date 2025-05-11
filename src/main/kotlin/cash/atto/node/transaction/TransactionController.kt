package cash.atto.node.transaction

import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoTransaction
import cash.atto.commons.node.AttoNodeOperations
import cash.atto.commons.toAttoHeight
import cash.atto.node.ApplicationProperties
import cash.atto.node.EventPublisher
import cash.atto.node.account.AccountUpdated
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.node.sortByHeight
import cash.atto.node.toBigInteger
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionPush
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
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
import java.math.BigDecimal
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
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoTransactionSample::class),
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
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoTransactionSample::class),
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
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoTransactionSample::class),
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

    @Operation(
        summary = "Stream transactions by account and height range",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoTransactionSample::class),
                    ),
                ],
            ),
        ],
    )
    @GetMapping("/accounts/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun streamMultiple(
        @RequestBody search: AttoNodeOperations.HeightSearch,
    ): Flow<AttoTransaction> {
        val accountRanges = search.search

        if (accountRanges.any { it.fromHeight == 0UL }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "fromHeight can't be zero")
        }
        if (accountRanges.any { it.fromHeight > it.toHeight }) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "toHeight must be greater or equal to fromHeight")
        }

        val accountFlows =
            accountRanges.map { range ->
                val publicKey = AttoAddress.parsePath(range.address).publicKey
                val fromHeight = range.fromHeight.toAttoHeight()
                val toHeight = range.toHeight.toAttoHeight()
                val expectedCount =
                    (range.toHeight - range.fromHeight + 1UL).let {
                        if (it > Int.MAX_VALUE.toUInt()) Int.MAX_VALUE else it.toInt()
                    }

                val dbFlow =
                    repository
                        .findAsc(publicKey, range.fromHeight.toBigInteger(), range.toHeight.toBigInteger())
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
                        mediaType = MediaType.APPLICATION_NDJSON_VALUE,
                        schema = Schema(implementation = AttoTransactionSample::class),
                    ),
                ],
            ),
        ],
    )
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "1", required = false) fromHeight: ULong,
        @RequestParam(required = false) toHeight: ULong?,
    ): Flow<AttoTransaction> {
        val transactionSearch =
            AttoNodeOperations.AccountHeightSearch(AttoAddress(AttoAlgorithm.V1, publicKey).path, fromHeight, toHeight ?: ULong.MAX_VALUE)
        val search = AttoNodeOperations.HeightSearch(listOf(transactionSearch))
        return streamMultiple(search)
    }

    @PostMapping("/transactions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Publish a transaction",
        requestBody =
            io.swagger.v3.oas.annotations.parameters.RequestBody(
                content = [
                    Content(
                        mediaType = MediaType.APPLICATION_JSON_VALUE,
                        schema = Schema(implementation = AttoTransactionSample::class),
                    ),
                ],
            ),
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
    @Operation(description = "Publish transaction and stream")
    suspend fun publishAndStream(
        @RequestBody transaction: AttoTransaction,
        request: ServerHttpRequest,
    ): Flow<AttoTransaction> =
        stream(transaction.hash)
            .onStart { publish(transaction, request) }
            .timeout(40.seconds)

    @Schema(name = "AttoBlock", description = "Base type for all block variants")
    @JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.PROPERTY,
        property = "type", // this must match the "type" field in your JSON
    )
    @JsonSubTypes(
        JsonSubTypes.Type(value = AttoSendBlockSample::class, name = "SEND"),
        JsonSubTypes.Type(value = AttoReceiveBlockSample::class, name = "RECEIVE"),
        JsonSubTypes.Type(value = AttoOpenBlockSample::class, name = "OPEN"),
        JsonSubTypes.Type(value = AttoChangeBlockSample::class, name = "CHANGE"),
    )
    sealed interface AttoBlockSample {
        val network: AttoNetwork
        val version: Int
        val algorithm: AttoAlgorithm
        val publicKey: String
        val balance: BigDecimal
        val timestamp: Long
    }

    @Schema(name = "AttoSendBlock", description = "Represents a SEND block")
    data class AttoSendBlockSample(
        override val network: AttoNetwork,
        override val version: Int,
        override val algorithm: AttoAlgorithm,
        override val publicKey: String,
        override val balance: BigDecimal,
        override val timestamp: Long,
        @Schema(description = "Height of the block", example = "2")
        val height: BigDecimal,
        @Schema(description = "Hash of the previous block", example = "6CC2D3A7513723B1BA59DE784BA546BAF6447464D0BA3D80004752D6F9F4BA23")
        val previous: String,
        @Schema(description = "Algorithm of the receiver", example = "V1")
        val receiverAlgorithm: AttoAlgorithm,
        @Schema(description = "Public key of the receiver", example = "552254E101B51B22080D084C12C94BF7DFC5BE0D973025D62C0BC1FF4D9B145F")
        val receiverPublicKey: String,
        @Schema(description = "Amount being sent", example = "1")
        val amount: BigDecimal,
    ) : AttoBlockSample

    @Schema(name = "AttoReceiveBlock", description = "Represents a RECEIVE block")
    data class AttoReceiveBlockSample(
        override val network: AttoNetwork,
        override val version: Int,
        override val algorithm: AttoAlgorithm,
        override val publicKey: String,
        override val balance: BigDecimal,
        override val timestamp: Long,
        @Schema(description = "Height of the block", example = "2")
        val height: BigDecimal,
        @Schema(description = "Hash of the previous block", example = "03783A08F51486A66A602439D9164894F07F150B548911086DAE4E4F57A9C4DD")
        val previous: String,
        @Schema(description = "Algorithm of the send block", example = "V1")
        val sendHashAlgorithm: AttoAlgorithm,
        @Schema(description = "Hash of the send block", example = "EE5FDA9A1ACEC7A09231792C345CDF5CD29F1059E5C413535D9FCA66A1FB2F49")
        val sendHash: String,
    ) : AttoBlockSample

    @Schema(name = "AttoOpenBlock", description = "Represents an OPEN block")
    data class AttoOpenBlockSample(
        override val network: AttoNetwork,
        override val version: Int,
        override val algorithm: AttoAlgorithm,
        override val publicKey: String,
        override val balance: BigDecimal,
        override val timestamp: Long,
        @Schema(description = "Algorithm of the send block", example = "V1")
        val sendHashAlgorithm: AttoAlgorithm,
        @Schema(description = "Hash of the send block", example = "4DC7257C0F492B8C7AC2D8DE4A6DC4078B060BB42FDB6F8032A839AAA9048DB0")
        val sendHash: String,
        @Schema(description = "Algorithm of the representative", example = "V1")
        val representativeAlgorithm: AttoAlgorithm,
        @Schema(
            description = "Public key of the representative",
            example = "69C010A8A74924D083D1FC8234861B4B357530F42341484B4EBDA6B99F047105",
        )
        val representativePublicKey: String,
    ) : AttoBlockSample

    @Schema(name = "AttoChangeBlock", description = "Represents a CHANGE block")
    data class AttoChangeBlockSample(
        override val network: AttoNetwork,
        override val version: Int,
        override val algorithm: AttoAlgorithm,
        override val publicKey: String,
        override val balance: BigDecimal,
        override val timestamp: Long,
        @Schema(description = "Height of the block", example = "2")
        val height: BigDecimal,
        @Schema(description = "Hash of the previous block", example = "AD675BD718F3D96F9B89C58A8BF80741D5EDB6741D235B070D56E84098894DD5")
        val previous: String,
        @Schema(description = "Algorithm of the representative", example = "V1")
        val representativeAlgorithm: AttoAlgorithm,
        @Schema(
            description = "Public key of the representative",
            example = "69C010A8A74924D083D1FC8234861B4B357530F42341484B4EBDA6B99F047105",
        )
        val representativePublicKey: String,
    ) : AttoBlockSample

    @Schema(name = "AttoTransaction", description = "A signed block")
    data class AttoTransactionSample(
        @Schema(description = "The block to be submitted (SEND, RECEIVE, OPEN, CHANGE)")
        val block: AttoBlockSample,
        @Schema(
            description = "Ed25519 signature of the block",
            example =
                "52843B36ABDFA4125E4C0D465A3C976C269F993C7C82645B29AB49B7A5A84FC41E" +
                    "3391D2A41C4CB83DFA3214DA87B099F86EF10402BFB1120A5D34F70CBC2B00",
        )
        val signature: String,
        @Schema(
            description = "Proof-of-work for the block",
            example = "4300FFFFFFFFFFCF",
        )
        val work: String,
    )
}
