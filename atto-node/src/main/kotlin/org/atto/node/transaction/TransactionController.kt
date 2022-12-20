package org.atto.node.transaction

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.oas.annotations.Operation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.atto.commons.*
import org.atto.node.EventPublisher
import org.atto.node.network.InboundNetworkMessage
import org.atto.node.network.NetworkMessagePublisher
import org.atto.protocol.AttoNode
import org.atto.protocol.transaction.AttoTransactionPush
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Async
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.math.BigInteger
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("/transactions")
class TransactionController(
    val node: AttoNode,
    val eventPublisher: EventPublisher,
    val messagePublisher: NetworkMessagePublisher,
    val repository: TransactionRepository
) {

    private val transactionPublisher = MutableSharedFlow<Transaction>()
    private val transactionFlow = transactionPublisher.asSharedFlow()

    @EventListener
    @Async
    fun process(transactionSaved: TransactionSaved) = runBlocking {
        transactionPublisher.emit(transactionSaved.transaction)
    }

    @GetMapping("/{hash}")
    @Operation(description = "Get transaction")
    suspend fun get(@PathVariable hash: AttoHash): ResponseEntity<Transaction> {
        val transaction = repository.findById(hash)
        return ResponseEntity.of(Optional.ofNullable(transaction))
    }

    @GetMapping("/{hash}/stream", produces = [MediaType.APPLICATION_STREAM_JSON_VALUE])
    @Operation(description = "Stream transaction")
    suspend fun stream(@PathVariable hash: AttoHash): Flow<Transaction> {
        val transactionDatabaseFlow: Flow<Transaction> = flow {
            val transaction = repository.findById(hash)
            if (transaction != null) {
                emit(transaction)
            }
        }
        return merge(transactionFlow, transactionDatabaseFlow)
            .first { it.hash == hash }
    }

    @PostMapping
    @Operation(description = "Publish transaction")
    suspend fun publish(@RequestBody transactionDTO: TransactionDTO): ResponseEntity<Void> {
        val attoTransaction = transactionDTO.toAttoTransaction()
        if (!attoTransaction.isValid(node.network)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build()
        }
        messagePublisher.publish(
            InboundNetworkMessage(
                node.socketAddress, //TODO: send with the user id
                AttoTransactionPush(attoTransaction)
            )
        )
        return ResponseEntity.ok().build()
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