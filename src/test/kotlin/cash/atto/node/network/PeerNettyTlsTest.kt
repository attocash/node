package cash.atto.node.network

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPrivateKey
import cash.atto.commons.AttoSignature
import cash.atto.commons.toSignerBlocking
import cash.atto.protocol.AttoNode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.URI
import java.nio.file.Path
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLServerSocket
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.time.Duration.Companion.seconds

class PeerNettyTlsTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `WSS and HTTPS verify certificates and hostname using the JVM trust store`() {
        // Given
        val keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString()
        for (name in listOf("trusted", "untrusted")) {
            command(
                name,
                listOf(
                    keytool,
                    "-genkeypair",
                    "-alias",
                    name,
                    "-keyalg",
                    "RSA",
                    "-keysize",
                    "2048",
                    "-dname",
                    "CN=localhost",
                    "-ext",
                    "SAN=dns:localhost",
                    "-validity",
                    "2",
                    "-keystore",
                    directory.resolve("$name.p12").toString(),
                    "-storetype",
                    "PKCS12",
                    "-storepass",
                    "atto-test",
                    "-keypass",
                    "atto-test",
                    "-noprompt",
                ),
            )
        }
        command(
            "export",
            listOf(
                keytool,
                "-exportcert",
                "-alias",
                "trusted",
                "-keystore",
                directory.resolve("trusted.p12").toString(),
                "-storepass",
                "atto-test",
                "-file",
                directory.resolve("trusted.cer").toString(),
            ),
        )
        command(
            "import",
            listOf(
                keytool,
                "-importcert",
                "-alias",
                "trusted",
                "-keystore",
                directory.resolve("trust.p12").toString(),
                "-storetype",
                "PKCS12",
                "-storepass",
                "atto-test",
                "-file",
                directory.resolve("trusted.cer").toString(),
                "-noprompt",
            ),
        )
        val classpath =
            checkNotNull(System.getProperty("atto.test.runtime-classpath")) {
                "The isolated TLS probe needs the test runtime classpath supplied by Gradle"
            }
        assertTrue(classpath.isNotEmpty(), "The isolated TLS probe needs the actual test runtime classpath")

        // When: use a fresh JVM so Reactor's cached default TLS context sees this trust store before initialization.
        val output =
            command(
                "probe",
                listOf(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Djavax.net.ssl.trustStore=${directory.resolve("trust.p12")}",
                    "-Djavax.net.ssl.trustStorePassword=atto-test",
                    "-Djavax.net.ssl.trustStoreType=PKCS12",
                    "-cp",
                    classpath,
                    PeerNettyTlsProbe::class.java.name,
                    directory.toString(),
                ),
            )

        // Then
        assertTrue(output.contains("Verified WSS and HTTPS trust and hostname checks"), output)
    }

    private fun command(
        name: String,
        arguments: List<String>,
    ): String {
        val log = directory.resolve("$name.log")
        val process = ProcessBuilder(arguments).redirectErrorStream(true).redirectOutput(log.toFile()).start()
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor(5, TimeUnit.SECONDS)
            error("TLS test command $name timed out: ${log.readText()}")
        }
        val output = log.readText()
        assertEquals(0, process.exitValue(), "TLS test command $name failed: $output")
        return output
    }
}

/** Executed in an isolated JVM; no trust settings or SSL contexts are changed in the Gradle test worker. */
object PeerNettyTlsProbe {
    private val json = Json { encodeDefaults = true }

    @JvmStatic
    fun main(arguments: Array<String>) =
        runBlocking {
            val directory = Path.of(arguments.single())
            val node =
                AttoNode(
                    network = AttoNetwork.LOCAL,
                    protocolVersion = AttoNode.CURRENT_PROTOCOL_VERSION,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPrivateKey.generate().toSignerBlocking().publicKey,
                    publicUri = URI("wss://localhost:7070"),
                    features = emptySet(),
                )
            val request =
                CounterChallengeResponse("00", AttoHash(ByteArray(32)), node, AttoInstant.now(), AttoSignature(ByteArray(64)), "00")
            val response = ChallengeResponse(node, request.timestamp, request.signature)
            val resolver = NetworkDnsResolver()
            val client = PeerNettyClient(resolver)
            try {
                val cases =
                    listOf(
                        Triple("trusted", "localhost", true),
                        Triple("trusted", "127.0.0.1", false),
                        Triple("untrusted", "localhost", false),
                    )
                for ((store, host, accepted) in cases) {
                    TlsPeer(directory.resolve("$store.p12"), json.encodeToString(response)).use { server ->
                        val websocket =
                            withTimeout(10.seconds) {
                                runCatching {
                                    client.webSocket(URI("wss://$host:${server.port}/"), emptyMap()) { session, address ->
                                        check(address.port == server.port)
                                        check(session.incoming.first().contentEquals(byteArrayOf(42)))
                                    }
                                }
                            }
                        val callback =
                            withTimeout(10.seconds) {
                                runCatching { client.postHandshake(URI("https://$host:${server.port}/handshakes"), request) }
                            }
                        check(websocket.isSuccess == accepted) { "WSS $store/$host: ${websocket.exceptionOrNull()}" }
                        check(callback.isSuccess == accepted) { "HTTPS $store/$host: ${callback.exceptionOrNull()}" }
                        if (accepted) {
                            check(callback.getOrThrow().response == response)
                        } else {
                            check(websocket.exceptionOrNull().isTlsRejection()) { "WSS failed outside TLS verification: $websocket" }
                            check(callback.exceptionOrNull().isTlsRejection()) { "HTTPS failed outside TLS verification: $callback" }
                        }
                    }
                }
            } finally {
                client.close()
                resolver.close()
            }
            println("Verified WSS and HTTPS trust and hostname checks")
        }

    private fun Throwable?.isTlsRejection(): Boolean = generateSequence(this) { it.cause }.any { it is SSLHandshakeException }

    private class TlsPeer(
        store: Path,
        private val callbackBody: String,
    ) : AutoCloseable {
        private val executor = Executors.newVirtualThreadPerTaskExecutor()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        private val server: SSLServerSocket
        val port: Int get() = server.localPort

        init {
            val keyStore = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "atto-test".toCharArray()) } }
            val managers =
                KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(keyStore, "atto-test".toCharArray()) }
            val context = SSLContext.getInstance("TLS").apply { init(managers.keyManagers, null, null) }
            server = context.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
            executor.submit {
                while (!server.isClosed) {
                    val socket =
                        try {
                            server.accept()
                        } catch (_: SocketException) {
                            break
                        }
                    sockets.add(socket)
                    executor.submit {
                        socket.use {
                            try {
                                socket.soTimeout = 10_000
                                respond(socket)
                            } catch (_: SSLException) {
                                // Untrusted and mismatched certificate cases terminate during TLS negotiation.
                            } catch (_: SocketException) {
                                // Client shutdown may reset a successfully upgraded connection.
                            } finally {
                                sockets.remove(socket)
                            }
                        }
                    }
                }
            }
        }

        private fun respond(socket: Socket) {
            val input = socket.getInputStream()
            val request = input.readLine()
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = input.readLine()
                if (line.isEmpty()) break
                headers[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
            }
            val output = socket.getOutputStream()
            if (request.startsWith("POST ")) {
                val length = checkNotNull(headers["content-length"]).toInt()
                check(input.readNBytes(length).size == length)
                val bytes = callbackBody.encodeToByteArray()
                output.write(
                    (
                        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    ).toByteArray(),
                )
                output.write(bytes)
            } else {
                val key = checkNotNull(headers["sec-websocket-key"]) + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
                val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest(key.toByteArray()))
                output.write(
                    (
                        "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                            "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n"
                    ).toByteArray(),
                )
                output.write(byteArrayOf(0x82.toByte(), 1, 42))
            }
            output.flush()
            if (!request.startsWith("POST ")) input.read()
        }

        override fun close() {
            server.close()
            sockets.forEach { it.close() }
            executor.shutdownNow()
        }
    }

    private fun InputStream.readLine(): String {
        val bytes = ArrayList<Byte>()
        while (true) {
            val value = read()
            check(value >= 0) { "Unexpected EOF reading TLS peer request" }
            if (value == 10) break
            bytes.add(value.toByte())
            check(bytes.size < 16_384)
        }
        return bytes.toByteArray().decodeToString().removeSuffix("\r")
    }
}
