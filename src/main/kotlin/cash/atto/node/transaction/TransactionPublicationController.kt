package cash.atto.node.transaction

import cash.atto.commons.AttoHash
import cash.atto.commons.AttoTransaction
import cash.atto.node.ApplicationProperties
import cash.atto.node.account.AccountUpdated
import cash.atto.node.election.ElectionExpired
import cash.atto.node.election.ElectionRejected
import cash.atto.node.network.InboundNetworkMessage
import cash.atto.node.network.MessageSource
import cash.atto.node.network.NetworkMessagePublisher
import cash.atto.protocol.AttoNode
import cash.atto.protocol.AttoTransactionPush
import io.github.oshai.kotlinlogging.KotlinLogging
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

@RestController
@RequestMapping
@Tag(
    name = "Transactions",
    description =
        "Submit or query transaction blocks. After an interrupted publication request, inspect the original transaction hash " +
            "and account history before creating another payment. A timeout does not prove that the transaction failed. " +
            "See [payment failure recovery](https://atto.cash/docs/whitepaper/technical#payment-failure-recovery).",
)
class TransactionPublicationController(
    val applicationProperties: ApplicationProperties,
    val thisNode: AttoNode,
    val messagePublisher: NetworkMessagePublisher,
    val repository: TransactionRepository,
) {
    private val logger = KotlinLogging.logger {}

    private val useXForwardedForKey = "X-FORWARDED-FOR"

    private val pendingTransactionStreams = ConcurrentHashMap<AttoHash, PendingTransactionStream>()

    @EventListener
    suspend fun process(accountUpdated: AccountUpdated) {
        val transaction = accountUpdated.transaction.toAttoTransaction()
        emitPending(transaction.hash, PendingTransactionResult.Confirmed(transaction))
    }

    @EventListener
    suspend fun process(rejection: TransactionRejected) {
        val reason = rejection.reason.toString()

        if (rejection.reason == TransactionRejectionReason.ALREADY_CONFIRMED) {
            emitPending(
                rejection.transaction.hash,
                PendingTransactionResult.Confirmed(rejection.transaction.toAttoTransaction()),
            )
        } else {
            emitPending(
                rejection.transaction.hash,
                PendingTransactionResult.Failed(
                    ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Transaction rejected due to $reason: ${rejection.message}",
                    ),
                ),
            )
        }
    }

    @EventListener
    suspend fun process(rejection: ElectionRejected) {
        val hash = rejection.transaction.hash
        val pending = pendingTransactionStreams[hash] ?: return
        val result =
            try {
                val confirmed = repository.findById(hash)
                if (confirmed != null) {
                    PendingTransactionResult.Confirmed(confirmed.toAttoTransaction())
                } else {
                    PendingTransactionResult.Failed(
                        ResponseStatusException(HttpStatus.CONFLICT, "Transaction rejected by local election"),
                    )
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                logger.error(exception) { "Failed to resolve rejected transaction $hash" }
                PendingTransactionResult.Failed(
                    ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Transaction confirmation lookup failed"),
                )
            }

        // Confirmation can complete this attempt while the repository read is suspended.
        if (pendingTransactionStreams.remove(hash, pending)) {
            pending.flow.emit(result)
        }
    }

    @EventListener
    suspend fun process(failure: TransactionValidationFailed) {
        emitPending(
            failure.transaction.hash,
            PendingTransactionResult.Failed(
                ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Transaction validation failed"),
            ),
        )
    }

    @EventListener
    suspend fun process(expired: ElectionExpired) {
        val reason = "ELECTION_EXPIRED"
        val message = "Election took too long"
        emitPending(
            expired.transaction.hash,
            PendingTransactionResult.Failed(
                ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Transaction rejected due to $reason: $message",
                ),
            ),
        )
    }

    @EventListener
    suspend fun process(dropped: TransactionDropped) {
        val reason = "TRANSACTION_DROPPED"
        val message = "Transaction queue dropped the transaction"
        emitPending(
            dropped.transaction.hash,
            PendingTransactionResult.Failed(
                ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Transaction rejected due to $reason: $message",
                ),
            ),
        )
    }

    @PostMapping("/transactions", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @Operation(
        summary = "Publish a transaction",
        description =
            "Validates and publishes the signed transaction, then waits for local confirmation and returns the confirmed transaction. " +
                "The confirmation wait has a 40-second timeout. After a timeout or lost response, inspect the original hash and " +
                "account history before creating another payment; confirmation may already have happened. " +
                "A confirmed Send creates a receivable; a separate Open or Receive adds it to the recipient's spendable balance.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "The confirmed transaction, including an already confirmed result when recognized by the node.",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid transaction, wrong network, invalid proxy-header configuration, or transaction rejection.",
                content = [Content()],
            ),
            ApiResponse(
                responseCode = "409",
                description = "The local election rejected this publication attempt and the hash was not found in local storage.",
                content = [Content()],
            ),
            ApiResponse(
                responseCode = "429",
                description = "The local transaction queue dropped the transaction. Check its hash before retrying the payment.",
                content = [Content()],
            ),
            ApiResponse(
                responseCode = "503",
                description = "The local election expired. This does not prove that the transaction failed on every node.",
                content = [Content()],
            ),
        ],
    )
    suspend fun publish(
        @RequestBody transaction: AttoTransaction,
        request: ServerHttpRequest,
        @Parameter(
            description =
                "When true, reuse a stored confirmation or an existing pending publication for the same transaction hash. " +
                    "This does not deduplicate payment attempts with different transaction hashes.",
        )
        @RequestParam(defaultValue = "false", required = false) deduplicate: Boolean = false,
    ): AttoTransaction = publishAndConfirm(transaction, request, deduplicate)

    private suspend fun publishAndConfirm(
        transaction: AttoTransaction,
        request: ServerHttpRequest,
        deduplicate: Boolean,
    ): AttoTransaction {
        logger.debug { "Received $transaction" }
        validate(transaction)

        if (deduplicate) {
            repository.findById(transaction.hash)?.let {
                return it.toAttoTransaction()
            }
        }

        val socketAddress = getSocketAddress(transaction, request)
        val pending = registerPending(transaction.hash)

        try {
            if (!deduplicate || pending.created) {
                publish(transaction, socketAddress)
            }

            return awaitPending(pending.stream)
        } finally {
            removePending(transaction.hash, pending.stream)
        }
    }

    private suspend fun validate(transaction: AttoTransaction) {
        if (!transaction.isValid()) {
            logger.debug { "Invalid! $transaction" }
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction")
        }

        if (transaction.block.network != thisNode.network) {
            logger.debug { "Invalid network! $transaction" }
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction network")
        }
    }

    private fun getSocketAddress(
        transaction: AttoTransaction,
        request: ServerHttpRequest,
    ): InetSocketAddress {
        val ips = request.headers[useXForwardedForKey] ?: listOf()
        val remoteAddress = request.remoteAddress!!

        return if (!applicationProperties.useXForwardedFor) {
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
    }

    private fun publish(
        transaction: AttoTransaction,
        socketAddress: InetSocketAddress,
    ) {
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
    @Operation(
        summary = "Publish a transaction and stream its confirmation",
        description =
            "Uses the same publication and local-confirmation operation as POST /transactions, " +
                "emitting one confirmed transaction as NDJSON. " +
                "The confirmation wait has a 40-second timeout. Inspect the emitted transaction, not only the initial HTTP status. " +
                "Errors after response commitment can terminate the stream; do not assume every timeout returns HTTP 504. " +
                "After an interrupted stream, inspect the original hash and account history before creating another payment.",
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "One confirmed transaction emitted as NDJSON.",
                content = [
                    Content(
                        schema = Schema(implementation = AttoTransaction::class),
                    ),
                ],
            ),
            ApiResponse(
                responseCode = "400",
                description = "Invalid or rejected transaction, or invalid proxy-header configuration, before response commitment.",
                content = [Content()],
            ),
            ApiResponse(
                responseCode = "409",
                description = "The local election rejected this publication attempt before response commitment.",
                content = [Content()],
            ),
            ApiResponse(
                responseCode = "429",
                description = "The local transaction queue dropped the transaction before response commitment.",
                content = [Content()],
            ),
            ApiResponse(
                responseCode = "503",
                description = "The local election expired before response commitment.",
                content = [Content()],
            ),
        ],
    )
    suspend fun publishAndStream(
        @RequestBody transaction: AttoTransaction,
        request: ServerHttpRequest,
        @Parameter(
            description =
                "When true, reuse a stored confirmation or an existing pending publication for the same transaction hash. " +
                    "This does not deduplicate payment attempts with different transaction hashes.",
        )
        @RequestParam(defaultValue = "false", required = false) deduplicate: Boolean = false,
    ): Flow<AttoTransaction> =
        flow {
            emit(publishAndConfirm(transaction, request, deduplicate))
        }

    private suspend fun awaitPending(stream: PendingTransactionStream): AttoTransaction =
        withTimeout(40.seconds) {
            stream
                .flow
                .map {
                    when (it) {
                        is PendingTransactionResult.Confirmed -> it.transaction
                        is PendingTransactionResult.Failed -> throw it.failure
                    }
                }.first()
        }

    private fun registerPending(hash: AttoHash): PendingTransactionRegistration {
        var created = false
        var stream: PendingTransactionStream? = null

        pendingTransactionStreams.compute(hash) { _, existing ->
            val value =
                existing ?: PendingTransactionStream().also {
                    created = true
                }
            value.waiters++
            stream = value
            value
        }

        return PendingTransactionRegistration(stream!!, created)
    }

    private fun removePending(
        hash: AttoHash,
        stream: PendingTransactionStream,
    ) {
        pendingTransactionStreams.computeIfPresent(hash) { _, existing ->
            if (existing === stream) {
                existing.waiters--
                existing.takeIf { it.waiters > 0 }
            } else {
                existing
            }
        }
    }

    private suspend fun emitPending(
        hash: AttoHash,
        result: PendingTransactionResult,
    ) {
        pendingTransactionStreams.remove(hash)?.flow?.emit(result)
    }

    private class PendingTransactionStream {
        val flow = MutableSharedFlow<PendingTransactionResult>(replay = 1)

        // Registration and removal update this count under the per-hash map computation.
        var waiters = 0
    }

    private class PendingTransactionRegistration(
        val stream: PendingTransactionStream,
        val created: Boolean,
    )

    private sealed interface PendingTransactionResult {
        data class Confirmed(
            val transaction: AttoTransaction,
        ) : PendingTransactionResult

        data class Failed(
            val failure: Throwable,
        ) : PendingTransactionResult
    }
}
