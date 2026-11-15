package cash.atto.node.transaction

import cash.atto.commons.AttoHeight
import cash.atto.node.ApplicationTestConfiguration
import cash.atto.node.Neighbour
import cash.atto.node.account.LegacyHttpFixtures
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

class TransactionStepDefinitionHttpTest {
    @Test
    fun `released neighbours use legacy paths and frozen JSON for reads and all publication block types`(): Unit =
        verifyHttpBoundary(released = true)

    @Test
    fun `classpath neighbours use encoded v2 paths and ignore blank heartbeats before and after records`(): Unit =
        verifyHttpBoundary(released = false)

    @Test
    fun `publication requires the response to confirm the submitted hash`() {
        // Given
        val submitted = LegacyHttpFixtures.transaction("send").toTransaction()
        val other = LegacyHttpFixtures.transaction("change")
        val server =
            HttpServer
                .create()
                .host("127.0.0.1")
                .port(0)
                .handle { _, response ->
                    response.header("Content-Type", "application/x-ndjson").sendString(Mono.just(Json.encodeToString(other) + "\n"))
                }.bindNow()
        try {
            val steps = TransactionStepDefinition(ApplicationTestConfiguration().webClient(false))
            // When
            val failure =
                assertThrows(IllegalStateException::class.java) {
                    steps.publish(Neighbour(0U, server.port().toUShort()), submitted)
                }
            // Then
            assertEquals(
                "Publication did not confirm ${submitted.hash} (neighbour=${Neighbour(0U, server.port().toUShort())})",
                failure.message,
            )
        } finally {
            server.disposeNow()
        }
    }

    @Test
    fun `v2 streaming still rejects malformed nonblank records`() {
        // Given
        val server =
            HttpServer
                .create()
                .host("127.0.0.1")
                .port(0)
                .handle { _, response ->
                    response.header("Content-Type", "application/x-ndjson").sendString(Mono.just("\n \t\nnot-json\n"))
                }.bindNow()
        try {
            val steps = TransactionStepDefinition(ApplicationTestConfiguration().webClient(false))
            // When / Then
            assertThrows(SerializationException::class.java) {
                steps.streamReceivable(
                    Neighbour(0U, server.port().toUShort()),
                    LegacyHttpFixtures.account.address.publicKey,
                    LegacyHttpFixtures.receivable.hash,
                )
            }
        } finally {
            server.disposeNow()
        }
    }

    private fun verifyHttpBoundary(released: Boolean) {
        // Given
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val publicationBodies = CopyOnWriteArrayList<JsonElement>()
        val server =
            HttpServer
                .create()
                .host("127.0.0.1")
                .port(0)
                .handle { request, response ->
                    requests.add(request.method().name() to request.uri())
                    val path = request.path()
                    if (request.method().name() == "POST") {
                        request.receive().aggregate().asString().flatMap { body ->
                            publicationBodies.add(Json.parseToJsonElement(body))
                            val output = if (released) body else "\n \t\n$body\n\n"
                            response
                                .header("Content-Type", if (released) "application/json" else "application/x-ndjson")
                                .sendString(Mono.just(output))
                                .then()
                        }
                    } else {
                        val fixture =
                            when {
                                path.endsWith("/entries/stream") -> "entry"
                                path.endsWith("/receivables/stream") -> "receivable"
                                path.contains("transactions/") -> "send"
                                else -> "account"
                            }
                        val record =
                            if (released) {
                                fixture(fixture)
                            } else {
                                when (fixture) {
                                    "account" -> Json.encodeToString(LegacyHttpFixtures.account)
                                    "entry" -> Json.encodeToString(LegacyHttpFixtures.entry)
                                    "receivable" -> Json.encodeToString(LegacyHttpFixtures.receivable)
                                    else -> Json.encodeToString(LegacyHttpFixtures.transaction("send"))
                                }
                            }
                        val streaming = path.endsWith("/stream")
                        response
                            .header("Content-Type", if (streaming) "application/x-ndjson" else "application/json")
                            .sendString(Mono.just(if (streaming && !released) "\n \t\n$record\n\n" else record + "\n"))
                    }
                }.bindNow()
        try {
            val neighbour = Neighbour(0U, server.port().toUShort(), released = released)
            val steps = TransactionStepDefinition(ApplicationTestConfiguration().webClient(false))
            val account = LegacyHttpFixtures.account
            val transaction = LegacyHttpFixtures.transaction("send")
            // When
            val actualAccount = steps.getAccount(neighbour, account.address.publicKey)
            val actualTransaction = steps.streamTransaction(neighbour, transaction.hash)
            val actualEntry =
                steps.streamAccountEntries(
                    neighbour,
                    account.address.publicKey,
                    LegacyHttpFixtures.entry.hash,
                    AttoHeight(2UL),
                )
            val actualReceivable = steps.streamReceivable(neighbour, account.address.publicKey, LegacyHttpFixtures.receivable.hash)
            val blockTypes = listOf("open", "receive", "send", "change")
            blockTypes.forEach { steps.publish(neighbour, LegacyHttpFixtures.transaction(it).toTransaction()) }
            // Then
            assertEquals(account, actualAccount)
            assertEquals(transaction, actualTransaction)
            assertEquals(LegacyHttpFixtures.entry, actualEntry)
            assertEquals(LegacyHttpFixtures.receivable, actualReceivable)
            val prefix = if (released) "" else "/v2"
            val accountId =
                if (released) {
                    account.address.publicKey.toString()
                } else {
                    URLEncoder.encode(
                        account.address.toString(),
                        StandardCharsets.UTF_8,
                    )
                }
            assertEquals(
                listOf(
                    "GET" to "$prefix/accounts/$accountId",
                    "GET" to "$prefix/transactions/${transaction.hash}/stream",
                    "GET" to "$prefix/accounts/$accountId/entries/stream?fromHeight=2&toHeight=2",
                    "GET" to "$prefix/accounts/$accountId/receivables/stream",
                ) + blockTypes.map { "POST" to "$prefix/transactions" },
                requests,
            )
            assertEquals(
                blockTypes.map {
                    if (released) {
                        Json.parseToJsonElement(
                            fixture(it),
                        )
                    } else {
                        Json.parseToJsonElement(Json.encodeToString(LegacyHttpFixtures.transaction(it)))
                    }
                },
                publicationBodies,
            )
        } finally {
            server.disposeNow()
        }
    }

    private fun fixture(name: String): String = checkNotNull(javaClass.getResource("/legacy-http/$name.json")).readText()
}
