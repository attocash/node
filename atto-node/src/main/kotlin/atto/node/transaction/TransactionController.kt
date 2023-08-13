package atto.node.transaction

import atto.node.ApplicationProperties
import atto.node.EventPublisher
import atto.node.network.InboundNetworkMessage
import atto.node.network.NetworkMessagePublisher
import atto.protocol.transaction.AttoTransactionPush
import cash.atto.commons.*
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.Operation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.scheduling.annotation.Async
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.math.BigInteger
import java.net.InetSocketAddress
import java.time.Instant
import java.util.*

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
    private val transactionPublisher = MutableSharedFlow<Transaction>(100_000)
    private val transactionFlow = transactionPublisher.asSharedFlow()

    @EventListener
    @Async
    fun process(transactionSaved: TransactionSaved) = runBlocking {
        transactionPublisher.emit(transactionSaved.transaction)
    }

    @GetMapping("/transactions/{hash}")
    @Operation(description = "Get transaction")
    suspend fun get(@PathVariable hash: AttoHash): ResponseEntity<Transaction> {
        val transaction = repository.findById(hash)
        return ResponseEntity.of(Optional.ofNullable(transaction))
    }

    @GetMapping("/transactions/{hash}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(description = "Stream a single transaction")
    suspend fun stream(@PathVariable hash: AttoHash): Flow<Transaction> {
        val transactionDatabaseFlow: Flow<Transaction> = flow {
            val transaction = repository.findById(hash)
            if (transaction != null) {
                emit(transaction)
            }
        }

        val transactionFlow = transactionFlow
            .filter { it.hash == hash }

        return merge(transactionFlow, transactionDatabaseFlow)
            .onStart { logger.trace { "Started streaming $hash transaction" } }
            .first()
            .let { flowOf(it) }
    }

    @GetMapping("/accounts/{publicKey}/transactions/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(description = "Stream unsorted transactions. Duplicates may happen")
    suspend fun stream(@PathVariable publicKey: AttoPublicKey, @RequestParam fromHeight: Long): Flow<Transaction> {
        val transactionDatabaseFlow = repository.findAsc(publicKey, fromHeight.toULong())

        val transactionFlow = transactionFlow
            .filter { it.publicKey == publicKey }
            .filter { it.block.height >= fromHeight.toULong() }

        return merge(transactionFlow, transactionDatabaseFlow)
            .onStart { logger.trace { "Started streaming transactions from $publicKey account and height equals or after $fromHeight" } }
    }

    @PostMapping("/transactions")
    @Operation(description = "Publish transaction")
    suspend fun publish(@RequestBody transactionDTO: TransactionDTO, request: ServerHttpRequest) {
        val attoTransaction = transactionDTO.toAttoTransaction()
        if (!attoTransaction.isValid(node.network)) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid transaction");
        }

        val ips = request.headers[useXForwardedForKey] ?: listOf()
        val remoteAddress = request.remoteAddress!!

        val socketAddress = if (applicationProperties.useXForwardedFor) {
            if (ips.isEmpty()) {
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "X-Forwarded-For is empty. Are you behind a proxy?"
                )
            }
            InetSocketAddress.createUnresolved(ips[0], remoteAddress.port)
        } else {
            if (ips.isNotEmpty()) {
                logger.debug { "Received a request with $useXForwardedForKey header" }
            }
            remoteAddress
        }

        messagePublisher.publish(
            InboundNetworkMessage(
                socketAddress,
                AttoTransactionPush(attoTransaction)
            )
        )
    }

    @PostMapping("/transactions/stream")
    @Operation(description = "Publish transaction and stream")
    suspend fun publishAndStream(@RequestBody transactionDTO: TransactionDTO, request: ServerHttpRequest): Transaction {
        val attoTransaction = transactionDTO.toAttoTransaction()
        return stream(attoTransaction.hash)
            .onStart { publish(transactionDTO, request) }
            .first()
    }
}

/**
 * The DTO's are required due to https://github.com/FasterXML/jackson-module-kotlin/issues/199
 */
data class TransactionDTO(
    val block: AttoBlockDTO,
    val signature: AttoSignature,
    val work: AttoWork
) {
    fun toAttoTransaction(): AttoTransaction {
        return AttoTransaction(block.toAttoBlock(), signature, work)
    }
}


@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    property = "type"
)
@JsonSubTypes(
    JsonSubTypes.Type(value = AttoSendBlockDTO::class, name = "SEND"),
    JsonSubTypes.Type(value = AttoReceiveBlockDTO::class, name = "RECEIVE"),
    JsonSubTypes.Type(value = AttoOpenBlockDTO::class, name = "OPEN"),
    JsonSubTypes.Type(value = AttoChangeBlockDTO::class, name = "CHANGE"),
)
interface AttoBlockDTO {
    val version: Short
    val publicKey: String
    val height: BigInteger
    val balance: BigInteger
    val timestamp: Instant

    fun toAttoBlock(): AttoBlock
}

data class AttoSendBlockDTO(
    override val version: Short,
    override val publicKey: String,
    override val height: BigInteger,
    override val balance: BigInteger,
    override val timestamp: Instant,
    val previous: String,
    val receiverPublicKey: String,
    val amount: BigDecimal,
) : AttoBlockDTO {
    override fun toAttoBlock(): AttoBlock {
        return AttoSendBlock(
            version = version.toUShort(),
            publicKey = AttoPublicKey.parse(publicKey),
            height = height.toLong().toULong(),
            balance = AttoAmount(balance.toLong().toULong()),
            timestamp = timestamp,
            previous = AttoHash.parse(previous),
            receiverPublicKey = AttoPublicKey.parse(receiverPublicKey),
            amount = AttoAmount(amount.toLong().toULong())
        )
    }

}

data class AttoReceiveBlockDTO(
    override val version: Short,
    override val publicKey: String,
    override val height: BigInteger,
    override val balance: BigInteger,
    override val timestamp: Instant,
    val previous: String,
    val sendHash: String,
) : AttoBlockDTO {
    override fun toAttoBlock(): AttoBlock {
        return AttoReceiveBlock(
            version = version.toUShort(),
            publicKey = AttoPublicKey.parse(publicKey),
            height = height.toLong().toULong(),
            balance = AttoAmount(balance.toLong().toULong()),
            timestamp = timestamp,
            previous = AttoHash.parse(previous),
            sendHash = AttoHash.parse(sendHash),
        )
    }
}

data class AttoOpenBlockDTO(
    override val version: Short,
    override val publicKey: String,
    override val balance: BigInteger,
    override val timestamp: Instant,
    val sendHash: String,
    val representative: String,
) : AttoBlockDTO {
    override val height: BigInteger = BigInteger.ZERO

    override fun toAttoBlock(): AttoBlock {
        return AttoOpenBlock(
            version = version.toUShort(),
            publicKey = AttoPublicKey.parse(publicKey),
            balance = AttoAmount(balance.toLong().toULong()),
            timestamp = timestamp,
            sendHash = AttoHash.parse(sendHash),
            representative = AttoPublicKey.parse(representative)
        )
    }
}


data class AttoChangeBlockDTO(
    override val version: Short,
    override val publicKey: String,
    override val height: BigInteger,
    override val balance: BigInteger,
    override val timestamp: Instant,
    val previous: String,
    val representative: String,
) : AttoBlockDTO {

    override fun toAttoBlock(): AttoBlock {
        return AttoChangeBlock(
            version = version.toUShort(),
            publicKey = AttoPublicKey.parse(publicKey),
            height = height.toLong().toULong(),
            balance = AttoAmount(balance.toLong().toULong()),
            timestamp = timestamp,
            previous = AttoHash.parse(previous),
            representative = AttoPublicKey.parse(representative),
        )
    }
}