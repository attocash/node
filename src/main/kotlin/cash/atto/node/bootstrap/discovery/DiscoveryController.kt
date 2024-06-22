package cash.atto.node.bootstrap.discovery

import io.swagger.v3.oas.annotations.Operation
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/unchecked-transactions/discoveries/")
@Profile("default")
class DiscoveryController(
    val gapDiscoverer: GapDiscoverer,
    val lastDiscoverer: LastDiscoverer,
) {
    @PostMapping("gap")
    @Operation(description = "Start processing of gap transaction")
    suspend fun gap() {
        gapDiscoverer.resolve()
    }

    @PostMapping("last")
    @Operation(description = "Start broadcast of last transactions")
    suspend fun tail() {
        lastDiscoverer.broadcastSample()
    }
}
