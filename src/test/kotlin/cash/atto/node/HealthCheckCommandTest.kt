package cash.atto.node

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class HealthCheckCommandTest {
    @Test
    fun `returns success when health endpoint is successful`() {
        // Given
        val response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"

        // When
        val (exitCode, requestLine) = executeAgainst(response)

        // Then
        assertEquals(0, exitCode)
        assertEquals("GET /health HTTP/1.1", requestLine)
    }

    @Test
    fun `returns failure when health endpoint is unavailable`() {
        // Given
        val response = "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\n\r\n"

        // When
        val (exitCode, _) = executeAgainst(response)

        // Then
        assertEquals(1, exitCode)
    }

    @Test
    fun `returns failure when management port is invalid`() {
        // Given
        val environment = mapOf("MANAGEMENT_SERVER_PORT" to "70000")

        // When
        val exitCode = HealthCheckCommand.execute(environment)

        // Then
        assertEquals(1, exitCode)
    }

    private fun executeAgainst(response: String): Pair<Int, String> =
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val requestLine =
                CompletableFuture.supplyAsync {
                    server.accept().use { connection ->
                        val reader = connection.getInputStream().bufferedReader(StandardCharsets.US_ASCII)
                        val firstLine = reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) {
                            // Consume the remaining request headers before responding.
                        }

                        connection.getOutputStream().apply {
                            write(response.toByteArray(StandardCharsets.US_ASCII))
                            flush()
                        }
                        firstLine
                    }
                }

            val exitCode =
                HealthCheckCommand.execute(
                    environment = mapOf("MANAGEMENT_SERVER_PORT" to server.localPort.toString()),
                    timeoutMillis = 1_000,
                )

            exitCode to requestLine.get(1, TimeUnit.SECONDS)
        }
}
