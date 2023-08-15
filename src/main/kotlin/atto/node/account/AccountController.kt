package atto.node.account

import atto.node.EventPublisher
import atto.node.transaction.TransactionSaved
import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoPublicKey
import io.swagger.v3.oas.annotations.Operation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.scheduling.annotation.Async
import org.springframework.web.bind.annotation.*
import java.math.BigInteger
import java.time.Instant
import java.util.*

@RestController
@RequestMapping("/accounts")
class AccountController(
    val node: atto.protocol.AttoNode,
    val eventPublisher: EventPublisher,
    val repository: AccountRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * There's a small chance that during subscription a client may miss the entry in the database and in the transaction
     * flow.
     *
     * The replay was added to workaround that. In any case, it's recommended to subscribe before publish transactions
     *
     */
    private val accountPublisher = MutableSharedFlow<Account>(100_000)
    private val accountFlow = accountPublisher.asSharedFlow()

    @EventListener
    @Async
    fun process(transactionSaved: TransactionSaved) = runBlocking {
        accountPublisher.emit(transactionSaved.updatedAccount)
    }

    @GetMapping("/{publicKey}")
    @Operation(description = "Get account")
    suspend fun get(@PathVariable publicKey: AttoPublicKey): ResponseEntity<Account> {
        val transaction = repository.findById(publicKey)
        return ResponseEntity.of(Optional.ofNullable(transaction))
    }

    @GetMapping("/{publicKey}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    @Operation(description = "Stream account unsorted. Duplicates may happen")
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "0") fromHeight: Long
    ): Flow<Account> {
        val accountDatabaseFlow = flow {
            val account = repository.findById(publicKey)
            if (account != null) {
                emit(account)
            }
        }
        val accountFlow = accountFlow
            .filter { it.publicKey == publicKey }

        return merge(accountFlow, accountDatabaseFlow)
            .filter { it.height >= fromHeight.toULong() }
            .onStart { logger.trace { "Started to stream $publicKey account" } }
    }
}

/**
 * The DTO's are required due to https://github.com/FasterXML/jackson-module-kotlin/issues/199
 */
data class AccountDTO(
    val publicKey: String,
    var version: Short,
    var height: BigInteger,
    var balance: BigInteger,
    var lastTransactionHash: String,
    var lastTransactionTimestamp: Instant,
    var representative: String,
) {
    fun toAttoAccount(): AttoAccount {
        return AttoAccount(
            publicKey = AttoPublicKey.parse(publicKey),
            version = version.toUShort(),
            height = height.toLong().toULong(),
            balance = AttoAmount(balance.toLong().toULong()),
            lastTransactionHash = AttoHash.parse(lastTransactionHash),
            lastTransactionTimestamp = lastTransactionTimestamp,
            representative = AttoPublicKey.parse(representative)
        )
    }
}