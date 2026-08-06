package cash.atto.node.unchecked

import cash.atto.commons.AttoTransaction
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionProcessor
import cash.atto.node.bootstrap.unchecked.UncheckedTransactionRepository
import io.swagger.v3.oas.annotations.Operation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/unchecked-transactions")
@Profile("default")
class UncheckedTransactionController(
    val uncheckedTransactionProcessor: UncheckedTransactionProcessor,
    val uncheckedTransactionRepository: UncheckedTransactionRepository,
) {
    @PostMapping
    @Operation(description = "Process unchecked transactions")
    suspend fun process() {
        uncheckedTransactionProcessor.process()
    }

    @GetMapping
    suspend fun get(): Flow<AttoTransaction> =
        uncheckedTransactionRepository
            .findAll()
            .map { it.toTransaction().toAttoTransaction() }
}
