package atto.node.account

import atto.node.EventPublisher
import atto.node.transaction.TransactionSaved
import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoPublicKey
import io.swagger.v3.oas.annotations.Operation
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

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
    private val accountPublisher = MutableSharedFlow<AttoAccount>(100_000)
    private val accountFlow = accountPublisher.asSharedFlow()

    @EventListener
    suspend fun process(transactionSaved: TransactionSaved) {
        accountPublisher.emit(transactionSaved.updatedAccount.toAttoAccount())
    }

    @GetMapping("/{publicKey}")
    @Operation(description = "Get account")
    suspend fun get(@PathVariable publicKey: AttoPublicKey): ResponseEntity<AttoAccount> {
        val account = repository.findById(publicKey)
        return ResponseEntity.ofNullable(account?.toAttoAccount())
    }

    @GetMapping("/{publicKey}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE + "+json"])
    @Operation(description = "Stream account unsorted. Duplicates may happen")
    suspend fun stream(
        @PathVariable publicKey: AttoPublicKey,
        @RequestParam(defaultValue = "0") fromHeight: Long
    ): Flow<String> {
        val accountDatabaseFlow = flow {
            val account = repository.findById(publicKey)
            if (account != null) {
                emit(account.toAttoAccount())
            }
        }
        val accountFlow = accountFlow
            .filter { it.publicKey == publicKey }

        return merge(accountFlow, accountDatabaseFlow)
            .filter { it.height >= fromHeight.toULong() }
            .onStart { logger.trace { "Started to stream $publicKey account" } }
            .map {
                Json.encodeToString(
                    AttoAccount.serializer(),
                    it
                )
            } //https://github.com/spring-projects/spring-framework/issues/30398

    }
}