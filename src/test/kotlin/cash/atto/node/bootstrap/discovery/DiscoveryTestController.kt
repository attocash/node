package cash.atto.node.bootstrap.discovery

import cash.atto.node.bootstrap.unchecked.UncheckedTransactionProcessor
import io.swagger.v3.oas.annotations.Operation
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/unchecked-transactions/discoveries")
class DiscoveryTestController(
    val discoveryPersistenceWorker: DiscoveryPersistenceWorker,
    val gapDiscoverer: GapDiscoverer,
    val lastDiscoverer: LastDiscoverer,
    val uncheckedTransactionProcessor: UncheckedTransactionProcessor,
) {
    @PostMapping("/gap")
    @Operation(description = "Start processing of gap transaction")
    suspend fun gap() {
        discoverGaps()
    }

    @PostMapping("/last")
    @Operation(description = "Start broadcast of last transactions")
    suspend fun last() {
        lastDiscoverer.broadcastSample()
    }

    @PostMapping("/flush")
    @Operation(description = "Flush discovered transactions")
    suspend fun flush() {
        flushAll()
    }

    @PostMapping("/settle")
    @Operation(description = "Flush discoveries, resolve gaps, and process unchecked transactions")
    suspend fun settle() {
        flushAll()
        uncheckedTransactionProcessor.process()
        discoverGaps()
        flushAll()
        uncheckedTransactionProcessor.process()
    }

    private suspend fun discoverGaps() {
        gapDiscoverer.discover()
    }

    private suspend fun flushAll() {
        while (discoveryPersistenceWorker.persistIfReady() == DiscoveryPersistenceResult.Persisted) {
            // Test-only endpoint: preserve the original deterministic full-drain behavior.
        }
    }
}
