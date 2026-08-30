package cash.atto.node

import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

internal object HealthCheckCommand {
    const val NAME = "healthcheck"

    private const val HOST = "127.0.0.1"
    private const val DEFAULT_MANAGEMENT_PORT = 8081
    private const val MANAGEMENT_PORT_ENVIRONMENT_VARIABLE = "MANAGEMENT_SERVER_PORT"
    private const val HEALTH_PATH = "/health"
    private const val DEFAULT_TIMEOUT_MILLIS = 2_000

    fun execute(
        environment: Map<String, String> = System.getenv(),
        timeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    ): Int =
        try {
            val port = managementPort(environment)
            val statusCode = requestStatusCode(port, timeoutMillis)
            if (statusCode in 200..299) {
                0
            } else {
                System.err.println("Health check failed: health endpoint returned HTTP $statusCode")
                1
            }
        } catch (exception: Exception) {
            System.err.println("Health check failed: ${exception.message ?: exception.javaClass.simpleName}")
            1
        }

    private fun managementPort(environment: Map<String, String>): Int {
        val configuredPort = environment[MANAGEMENT_PORT_ENVIRONMENT_VARIABLE] ?: return DEFAULT_MANAGEMENT_PORT
        val port = configuredPort.toInt()
        require(port in 1..65535) { "$MANAGEMENT_PORT_ENVIRONMENT_VARIABLE must be between 1 and 65535" }
        return port
    }

    private fun requestStatusCode(
        port: Int,
        timeoutMillis: Int,
    ): Int {
        require(timeoutMillis > 0) { "Health check timeout must be positive" }

        Socket().use { socket ->
            socket.connect(InetSocketAddress(HOST, port), timeoutMillis)
            socket.soTimeout = timeoutMillis

            val request =
                "GET $HEALTH_PATH HTTP/1.1\r\n" +
                    "Host: $HOST:$port\r\n" +
                    "Connection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray(StandardCharsets.US_ASCII))
                flush()
            }

            val statusLine = socket.getInputStream().bufferedReader(StandardCharsets.US_ASCII).readLine()
            val statusCode = statusLine?.split(' ', limit = 3)?.getOrNull(1)?.toIntOrNull()
            return requireNotNull(statusCode) { "Health endpoint returned an invalid HTTP response" }
        }
    }
}
