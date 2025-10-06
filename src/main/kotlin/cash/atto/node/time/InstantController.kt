package cash.atto.node.time

import cash.atto.commons.node.TimeDifferenceResponse
import cash.atto.commons.toAtto
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant
import kotlin.time.ExperimentalTime

@RestController
@RequestMapping("/instants")
@Tag(
    name = "Instants",
    description = "Returns the time difference between the server and client. Useful for clients with unreliable or skewed clocks.",
)
class InstantController {
    @OptIn(ExperimentalTime::class)
    @GetMapping("/{clientInstant}")
    @Operation(summary = "Return time adjustment to send transactions")
    suspend fun get(
        @PathVariable clientInstant: Instant,
    ): TimeDifferenceResponse {
        val serverInstant = Instant.now()
        val difference = Duration.between(clientInstant, serverInstant)
        return TimeDifferenceResponse(
            clientInstant = clientInstant.toAtto(),
            serverInstant = serverInstant.toAtto(),
            differenceMillis = difference.toMillis(),
        )
    }
}
