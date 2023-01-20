package org.atto.node.bootstrap.discovery

import io.swagger.v3.oas.annotations.Operation
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/transactions/uncheckeds/discoveries")
@Profile("default")
class DiscoveryController(
    val gapDiscoverer: GapDiscoverer
) {
    @PostMapping("gap")
    @Operation(description = "Start processing o gap transaction")
    suspend fun process() {
        gapDiscoverer.resolve()
    }
}