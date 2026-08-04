package cash.atto.node.bootstrap.discovery

import cash.atto.node.bootstrap.unchecked.UncheckedTransactionProcessorStarter
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
    val uncheckedTransactionProcessorStarter: UncheckedTransactionProcessorStarter,
) {
    @PostMapping("/gap")
    @Operation(description = "Start processing of gap transaction")
    suspend fun gap() {
        gapDiscoverer.resolve()
    }

    @PostMapping("/last")
    @Operation(description = "Start broadcast of last transactions")
    suspend fun last() {
        lastDiscoverer.broadcastSample()
    }

    @PostMapping("/flush")
    @Operation(description = "Flush discovered transactions")
    suspend fun flush() {
        discoveryPersistenceWorker.flush()
    }

    @PostMapping("/settle")
    @Operation(description = "Flush discoveries, resolve gaps, and process unchecked transactions")
    suspend fun settle() {
        discoveryPersistenceWorker.flush()
        uncheckedTransactionProcessorStarter.process()
        gapDiscoverer.resolve()
        discoveryPersistenceWorker.flush()
        uncheckedTransactionProcessorStarter.process()
    }
}
