package cash.atto.node.time

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

@RestController
@RequestMapping("/instants")
@Tag(
    name = "Instants",
    description = "Returns the time difference between the server and client. Useful for clients with unreliable or skewed clocks.",
)
class InstantController {
    @GetMapping("/{clientInstant}")
    @Operation(summary = "Return time adjustment to send transactions")
    suspend fun get(
        @PathVariable clientInstant: Instant,
    ): TimeResponse {
        val serverInstant = Instant.now()
        return TimeResponse(
            clientInstant = clientInstant,
            serverInstant = serverInstant,
            differenceMillis = Duration.between(clientInstant, serverInstant).toMillis(),
        )
    }

    data class TimeResponse(
        val clientInstant: Instant,
        val serverInstant: Instant,
        val differenceMillis: Long,
    )
}
