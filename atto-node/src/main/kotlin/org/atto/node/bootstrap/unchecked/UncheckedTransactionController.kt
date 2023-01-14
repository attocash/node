package org.atto.node.bootstrap.unchecked

import io.swagger.v3.oas.annotations.Operation
import kotlinx.coroutines.flow.Flow
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/transactions/uncheckeds")
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
    suspend fun get(): Flow<UncheckedTransaction> {
        return uncheckedTransactionRepository.findAll()
    }
}