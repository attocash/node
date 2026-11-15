package cash.atto.node

import cash.atto.commons.AttoAccount
import cash.atto.commons.AttoAccountEntry
import cash.atto.commons.AttoAddress
import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoBlockType
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoHeight
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceivable
import cash.atto.commons.AttoSendBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoTransaction
import cash.atto.commons.AttoWork
import cash.atto.commons.node.AccountSearch
import cash.atto.commons.spring.conversion.AttoConversionsAutoConfiguration
import cash.atto.commons.spring.schema.AttoSpringDocOpenApiAutoConfiguration
import cash.atto.commons.toAttoVersion
import cash.atto.node.account.AccountController
import cash.atto.node.account.AccountQueries
import cash.atto.node.account.LegacyHttpFixtures
import cash.atto.node.account.entry.AccountEntryController
import cash.atto.node.account.entry.AccountEntryQueries
import cash.atto.node.receivable.ReceivableController
import cash.atto.node.receivable.ReceivableQueries
import cash.atto.node.transaction.TransactionController
import cash.atto.node.transaction.TransactionPublicationController
import cash.atto.node.transaction.TransactionQueries
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springdoc.core.configuration.SpringDocConfiguration
import org.springdoc.core.configuration.SpringDocKotlinxConfiguration
import org.springdoc.core.properties.SpringDocConfigProperties
import org.springdoc.webflux.core.configuration.SpringDocWebFluxConfiguration
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory
import org.springframework.boot.web.context.reactive.AnnotationConfigReactiveWebApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerCodecConfigurer
import org.springframework.web.reactive.config.EnableWebFlux
import org.springframework.web.reactive.config.WebFluxConfigurer
import org.springframework.web.server.adapter.WebHttpHandlerBuilder
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class HttpApiTest {
    @Test
    fun `encoded address routes delegate the public key and preserve query defaults and legacy routes`() {
        // Given
        withServer { baseUrl, configuration ->
            val publicKey = AttoPublicKey(ByteArray(32) { 1 })
            val address = AttoAddress(AttoAlgorithm.V1, publicKey)
            val encodedAddress = URLEncoder.encode(address.toString(), StandardCharsets.UTF_8)
            HttpClient.newHttpClient().use { client ->
                // When
                val scalar = get(client, "$baseUrl/v2/accounts/$encodedAddress")
                val account = get(client, "$baseUrl/v2/accounts/$encodedAddress/stream")
                val receivables = get(client, "$baseUrl/v2/accounts/$encodedAddress/receivables/stream?minAmount=7")
                val entries = get(client, "$baseUrl/v2/accounts/$encodedAddress/entries/stream?fromHeight=2&toHeight=5")
                val transactions = get(client, "$baseUrl/v2/accounts/$encodedAddress/transactions/stream")
                val defaultReceivables = get(client, "$baseUrl/v2/accounts/$encodedAddress/receivables/stream")
                val defaultEntries = get(client, "$baseUrl/v2/accounts/$encodedAddress/entries/stream")
                val legacy = get(client, "$baseUrl/accounts/$publicKey")
                val legacyStream = get(client, "$baseUrl/accounts/$publicKey/stream")

                // Then
                assertEquals(404, scalar.statusCode())
                assertEquals(404, legacy.statusCode())
                for (response in listOf(account, receivables, entries, transactions, defaultReceivables, defaultEntries, legacyStream)) {
                    assertEquals(200, response.statusCode())
                    assertEquals("", response.body())
                }
                coVerify(exactly = 2) { configuration.accounts.get(publicKey) }
                coVerify(exactly = 2) { configuration.accounts.stream(publicKey) }
                coVerify(exactly = 1) { configuration.receivables.stream(publicKey, AttoAmount(7UL)) }
                coVerify(exactly = 1) { configuration.receivables.stream(publicKey, AttoAmount(1UL)) }
                coVerify(exactly = 1) { configuration.entries.stream(publicKey, AttoHeight(2UL), AttoHeight(5UL)) }
                coVerify(exactly = 1) { configuration.entries.stream(publicKey, AttoHeight(1UL), null) }
                coVerify(exactly = 1) { configuration.transactions.stream(publicKey, AttoHeight(1UL), AttoHeight.MAX) }
            }
        }
    }

    @Test
    fun `HTTP account and stream responses serialize address only while legacy payload stays unchanged`() {
        // Given
        withServer { baseUrl, configuration ->
            val key = AttoPublicKey(ByteArray(32) { 1 })
            val otherKey = AttoPublicKey(ByteArray(32) { 2 })
            val address = AttoAddress(AttoAlgorithm.V1, key)
            val algorithm = AttoAlgorithm.V1
            val version = 0U.toAttoVersion()
            val timestamp = AttoInstant.now()
            val hash = AttoHash(ByteArray(32) { 3 })
            val account =
                AttoAccount(
                    network = AttoNetwork.LOCAL,
                    version = version,
                    address = AttoAddress(algorithm, key),
                    height = AttoHeight(2UL),
                    balance = AttoAmount(10UL),
                    lastTransactionHash = hash,
                    lastTransactionTimestamp = timestamp,
                    representativeAddress = AttoAddress(algorithm, otherKey),
                )
            val receivable =
                AttoReceivable(
                    network = AttoNetwork.LOCAL,
                    hash = hash,
                    version = version,
                    address = AttoAddress(algorithm, key),
                    timestamp = timestamp,
                    receiverAddress = AttoAddress(algorithm, otherKey),
                    amount = AttoAmount(3UL),
                )
            val entry =
                AttoAccountEntry(
                    hash = hash,
                    address = AttoAddress(algorithm, key),
                    height = AttoHeight(2UL),
                    blockType = AttoBlockType.SEND,
                    subjectAddress = AttoAddress(algorithm, otherKey),
                    previousBalance = AttoAmount(13UL),
                    balance = AttoAmount(10UL),
                    timestamp = timestamp,
                )
            val block =
                AttoSendBlock(
                    network = AttoNetwork.LOCAL,
                    version = version,
                    address = AttoAddress(algorithm, key),
                    height = AttoHeight(2UL),
                    balance = AttoAmount(10UL),
                    timestamp = timestamp,
                    previous = hash,
                    receiverAddress = AttoAddress(algorithm, otherKey),
                    amount = AttoAmount(3UL),
                )
            val transaction = AttoTransaction(block, AttoSignature(ByteArray(AttoSignature.SIZE)), AttoWork(ByteArray(AttoWork.SIZE)))
            coEvery { configuration.accounts.get(key) } returns ResponseEntity.ok(account)
            coEvery { configuration.accounts.stream(key) } returns flowOf(account)
            coEvery { configuration.receivables.stream(key, any()) } returns flowOf(receivable)
            coEvery { configuration.entries.stream(key, any(), any()) } returns flowOf(entry)
            coEvery { configuration.transactions.stream(key, any(), any()) } returns flowOf(transaction)
            val encoded = URLEncoder.encode(address.toString(), StandardCharsets.UTF_8)
            HttpClient.newHttpClient().use { client ->
                // When
                val responses =
                    listOf("", "/stream", "/receivables/stream", "/entries/stream", "/transactions/stream")
                        .map { suffix -> get(client, "$baseUrl/v2/accounts/$encoded$suffix") }
                val legacy = get(client, "$baseUrl/accounts/$key")

                // Then
                for ((index, response) in responses.withIndex()) {
                    assertEquals(200, response.statusCode())
                    assertFalse(response.body().contains("publicKey", ignoreCase = true))
                    assertFalse(response.body().contains("\"algorithm\""))
                    saveEvidence("response-$index.json", response.body())
                    val record = Json.parseToJsonElement(response.body().trim()).jsonObject
                    val identity = if (index == 4) record.getValue("block").jsonObject else record
                    assertEquals("\"$address\"", identity.getValue("address").toString())
                    val mediaType = response.headers().firstValue("Content-Type").orElse("")
                    assertTrue(mediaType.startsWith(if (index == 0) "application/json" else "application/x-ndjson"))
                }
                assertEquals(200, legacy.statusCode())
                saveEvidence("legacy-account.json", legacy.body())
                assertTrue(legacy.body().contains("publicKey"))
                assertTrue(legacy.body().contains("representativePublicKey"))
            }
        }
    }

    @Test
    fun `invalid and double encoded addresses fail binding without calling account handlers`() {
        // Given
        withServer { baseUrl, configuration ->
            val address = AttoAddress(AttoAlgorithm.V1, AttoPublicKey(ByteArray(32) { 1 }))
            val encodedAddress = URLEncoder.encode(address.toString(), StandardCharsets.UTF_8)
            val doubleEncodedAddress = URLEncoder.encode(encodedAddress, StandardCharsets.UTF_8)
            HttpClient.newHttpClient().use { client ->
                // When
                val invalid = get(client, "$baseUrl/v2/accounts/not-an-address")
                val doubleEncoded = get(client, "$baseUrl/v2/accounts/$doubleEncodedAddress")

                // Then
                assertEquals(400, invalid.statusCode())
                assertEquals(400, doubleEncoded.statusCode())
                coVerify(exactly = 0) { configuration.accounts.get(any<AttoPublicKey>()) }
            }
        }
    }

    @Test
    fun `generated OpenAPI shows address responses and hides public key routes`() {
        // Given
        withServer { baseUrl, _ ->
            HttpClient.newHttpClient().use { client ->
                // When
                val response = get(client, "$baseUrl/v3/api-docs")

                // Then
                assertEquals(200, response.statusCode())
                saveEvidence("openapi.json", response.body())
                val document = Json.parseToJsonElement(response.body()).jsonObject
                val paths = document.getValue("paths").jsonObject
                for (suffix in listOf("", "/stream", "/receivables/stream", "/entries/stream", "/transactions/stream")) {
                    assertTrue(paths.containsKey("/v2/accounts/{address}$suffix"))
                    assertFalse(paths.containsKey("/accounts/{publicKey}$suffix"))
                }
                assertFalse(paths.getValue("/v2/transactions").jsonObject.containsKey("get"))
                assertTrue(paths.getValue("/v2/transactions").jsonObject.containsKey("post"))
                assertTrue(paths.getValue("/v2/transactions/stream").jsonObject.containsKey("get"))
                assertFalse(paths.getValue("/v2/transactions/stream").jsonObject.containsKey("post"))
                assertTrue(paths.filterKeys { it.endsWith("/stream") }.keys.all { it.startsWith("/v2/") })
                for (path in listOf("/transactions", "/v2/transactions")) {
                    val publication =
                        paths
                            .getValue(path)
                            .jsonObject
                            .getValue("post")
                            .jsonObject
                    val parameters = publication.getValue("parameters") as JsonArray
                    val deduplicate = parameters.map { it.jsonObject }.single { it.getValue("name").jsonPrimitive.content == "deduplicate" }
                    assertEquals(
                        "When true, reuse a stored confirmation or an existing pending publication for the same transaction hash. " +
                            "This does not deduplicate payment attempts with different transaction hashes.",
                        deduplicate.getValue("description").jsonPrimitive.content,
                    )
                }
                assertEquals(
                    "getAccountsByAddresses",
                    paths
                        .getValue("/v2/accounts")
                        .jsonObject
                        .getValue("post")
                        .jsonObject
                        .getValue("operationId")
                        .jsonPrimitive.content,
                )
                val batchSchema =
                    paths
                        .getValue("/v2/accounts")
                        .jsonObject
                        .getValue("post")
                        .jsonObject
                        .getValue("responses")
                        .jsonObject
                        .getValue("200")
                        .jsonObject
                        .getValue("content")
                        .jsonObject
                        .getValue("application/json")
                        .jsonObject
                        .getValue("schema")
                        .jsonObject
                assertEquals("array", batchSchema["type"]?.jsonPrimitive?.content, batchSchema.toString())
                assertEquals(
                    "#/components/schemas/AttoAccount",
                    batchSchema
                        .getValue("items")
                        .jsonObject
                        .getValue("\$ref")
                        .jsonPrimitive.content,
                )
                val publicationSchema =
                    paths
                        .getValue("/transactions")
                        .jsonObject
                        .getValue("post")
                        .jsonObject
                        .getValue(
                            "requestBody",
                        ).jsonObject
                        .getValue("content")
                        .jsonObject
                        .getValue("application/json")
                        .jsonObject
                        .getValue("schema")
                        .jsonObject
                assertEquals("#/components/schemas/AttoTransaction", publicationSchema.getValue("\$ref").jsonPrimitive.content)
                val schemas =
                    document
                        .getValue("components")
                        .jsonObject
                        .getValue("schemas")
                        .jsonObject

                fun propertiesOf(schema: JsonObject): Map<String, JsonElement> {
                    val inherited = mutableMapOf<String, JsonElement>()
                    (schema["allOf"] as? JsonArray)?.forEach { part ->
                        val objectPart = part.jsonObject
                        val reference = objectPart["\$ref"]?.jsonPrimitive?.content
                        val resolved = if (reference == null) objectPart else schemas.getValue(reference.substringAfterLast('/')).jsonObject
                        inherited.putAll(propertiesOf(resolved))
                    }
                    (schema["properties"] as? JsonObject)?.let { inherited.putAll(it) }
                    return inherited
                }
                for (name in listOf(
                    "AttoAccount",
                    "AttoReceivable",
                    "AttoAccountEntry",
                    "AttoOpenBlock",
                    "AttoReceiveBlock",
                    "AttoSendBlock",
                    "AttoChangeBlock",
                )) {
                    val properties = propertiesOf(schemas.getValue(name).jsonObject)
                    assertTrue(properties.containsKey("address"), name)
                    assertFalse(properties.keys.any { it.endsWith("publicKey", ignoreCase = true) }, name)
                }
                val visited = mutableSetOf<String>()

                fun verifyReachableSchema(element: JsonElement) {
                    when (element) {
                        is JsonObject -> {
                            val properties = element["properties"] as? JsonObject
                            assertFalse(properties?.keys?.any { it.endsWith("publicKey", ignoreCase = true) } == true)
                            element["\$ref"]?.jsonPrimitive?.content?.let { reference ->
                                val name = reference.substringAfterLast('/')
                                assertFalse(name == "AttoPublicKey")
                                if (visited.add(name)) verifyReachableSchema(schemas.getValue(name))
                            }
                            element.values.forEach { verifyReachableSchema(it) }
                        }

                        is JsonArray -> {
                            element.forEach { verifyReachableSchema(it) }
                        }

                        else -> {
                            Unit
                        }
                    }
                }
                paths.filterKeys { it.startsWith("/v2/") }.values.forEach { verifyReachableSchema(it) }
                for ((name, expectedTypes) in mapOf(
                    "AttoBlock" to setOf("OPEN", "RECEIVE", "SEND", "CHANGE"),
                    "AttoOpenBlock" to setOf("OPEN"),
                    "AttoReceiveBlock" to setOf("RECEIVE"),
                    "AttoSendBlock" to setOf("SEND"),
                    "AttoChangeBlock" to setOf("CHANGE"),
                )) {
                    val schema = schemas.getValue(name).jsonObject
                    val type = propertiesOf(schema).getValue("type").jsonObject
                    assertEquals(expectedTypes, (type.getValue("enum") as JsonArray).map { it.jsonPrimitive.content }.toSet())
                    assertTrue((schema.getValue("required") as JsonArray).any { it.jsonPrimitive.content == "type" }, name)
                }
                val baseBlock = schemas.getValue("AttoBlock").jsonObject
                assertFalse(baseBlock.containsKey("oneOf"))
                assertFalse(baseBlock.containsKey("anyOf"))
                val discriminator =
                    schemas
                        .getValue("AttoBlock")
                        .jsonObject
                        .getValue("discriminator")
                        .jsonObject
                assertEquals("\"type\"", discriminator.getValue("propertyName").toString())
                assertEquals(setOf("OPEN", "RECEIVE", "SEND", "CHANGE"), discriminator.getValue("mapping").jsonObject.keys)
                discriminator.getValue("mapping").jsonObject.values.forEach { target ->
                    assertTrue(schemas.containsKey(target.jsonPrimitive.content.substringAfterLast('/')))
                }
            }
        }
    }

    @Test
    fun `v2 flushes blank heartbeats while legacy delays its first byte and both complete once`() {
        // Given
        withServer { baseUrl, configuration ->
            val account = LegacyHttpFixtures.account
            val subscriptions = AtomicInteger()
            coEvery { configuration.accounts.stream(account.address.publicKey) } returns
                flow {
                    subscriptions.incrementAndGet()
                    delay(2200)
                    emit(account)
                }
            val encoded = URLEncoder.encode(account.address.toString(), StandardCharsets.UTF_8)
            HttpClient.newHttpClient().use { client ->
                // When
                val canonical = get(client, "$baseUrl/v2/accounts/$encoded/stream")
                val legacy = get(client, "$baseUrl/accounts/${account.address.publicKey}/stream")

                // Then
                assertEquals(200, canonical.statusCode())
                assertTrue(canonical.body().startsWith("\n\n"), canonical.body())
                assertEquals(1, canonical.body().lineSequence().count { it.isNotBlank() })
                assertTrue(legacy.body().startsWith("{"), legacy.body())
                assertEquals(1, legacy.body().lineSequence().count { it.isNotBlank() })
                assertEquals(2, subscriptions.get())
            }
        }
    }

    @Test
    fun `disconnecting a v2 heartbeat stream cancels the single upstream collection`(): Unit =
        runBlocking {
            // Given
            withServer { baseUrl, configuration ->
                val address = AttoAddress(AttoAlgorithm.V1, AttoPublicKey(ByteArray(32) { 1 }))
                val subscriptions = AtomicInteger()
                val cancelled = CompletableDeferred<Unit>()
                coEvery { configuration.accounts.stream(address.publicKey) } returns
                    flow {
                        subscriptions.incrementAndGet()
                        try {
                            awaitCancellation()
                        } finally {
                            cancelled.complete(Unit)
                        }
                    }
                val encoded = URLEncoder.encode(address.toString(), StandardCharsets.UTF_8)
                HttpClient.newHttpClient().use { client ->
                    // When
                    val response =
                        client.send(
                            HttpRequest.newBuilder(URI("$baseUrl/v2/accounts/$encoded/stream")).GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream(),
                        )
                    response.body().use { assertEquals('\n'.code, it.read()) }

                    // Then
                    runBlocking { withTimeout(5000) { cancelled.await() } }
                    assertEquals(1, subscriptions.get())
                }
            }
        }

    @Test
    fun `all versioned stream aliases return canonical models and legacy aliases preserve their fields`() {
        // Given
        withServer { baseUrl, configuration ->
            fun fixture(name: String) = checkNotNull(javaClass.getResource("/legacy-http/$name.json")).readText()
            val account = LegacyHttpFixtures.account
            val entry = LegacyHttpFixtures.entry
            val receivable = LegacyHttpFixtures.receivable
            val transaction = LegacyHttpFixtures.transaction("send")
            coEvery { configuration.accounts.stream() } returns flowOf(account)
            coEvery { configuration.accounts.stream(any<AccountSearch>()) } returns flowOf(account)
            coEvery { configuration.entries.stream() } returns flowOf(entry)
            coEvery { configuration.entries.stream(any<AttoHash>()) } returns flowOf(entry)
            coEvery { configuration.entries.streamMultiple(any()) } returns flowOf(entry)
            coEvery { configuration.receivables.stream(any<AccountSearch>(), any()) } returns flowOf(receivable)
            coEvery { configuration.transactions.stream() } returns flowOf(transaction)
            coEvery { configuration.transactions.stream(any<AttoHash>()) } returns flowOf(transaction)
            coEvery { configuration.transactions.streamMultiple(any()) } returns flowOf(transaction)
            coEvery { configuration.publication.publishAndStream(any(), any(), any()) } returns flowOf(transaction)
            val search = "{\"addresses\":[\"${account.address}\"]}"
            val range = "{\"search\":[{\"address\":\"${account.address}\",\"fromHeight\":1,\"toHeight\":2}]}"
            val routes =
                listOf(
                    Triple("/accounts/stream", null, "account"),
                    Triple("/accounts/stream", search, "account"),
                    Triple("/accounts/receivables/stream", search, "receivable"),
                    Triple("/accounts/entries/stream", null, "entry"),
                    Triple("/accounts/entries/stream", range, "entry"),
                    Triple("/accounts/entries/${entry.hash}/stream", null, "entry"),
                    Triple("/accounts/transactions/stream", range, "send"),
                    Triple("/transactions/stream", null, "send"),
                    Triple("/transactions/${transaction.hash}/stream", null, "send"),
                    Triple("/transactions/stream", fixture("send"), "send"),
                )
            HttpClient.newHttpClient().use { client ->
                for ((path, body, expected) in routes) {
                    fun request(prefix: String): HttpResponse<String> {
                        val resolvedPath = if (prefix == "/v2" && path == "/transactions/stream" && body != null) "/transactions" else path
                        val builder = HttpRequest.newBuilder(URI("$baseUrl$prefix$resolvedPath")).timeout(Duration.ofSeconds(5))
                        if (body ==
                            null
                        ) {
                            builder.GET()
                        } else {
                            builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
                        }
                        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
                    }

                    // When
                    val canonical = request("/v2")
                    val legacy = request("")

                    // Then
                    assertEquals(200, canonical.statusCode(), path + canonical.body())
                    assertEquals(200, legacy.statusCode(), path + legacy.body())
                    assertFalse(canonical.body().contains("publicKey", ignoreCase = true), path)
                    assertFalse(canonical.body().contains("\"algorithm\""), path)
                    assertEquals(fixture(expected), legacy.body().trim(), path)
                }
            }
        }
    }

    @Test
    fun `publication uses the v2 collection path and flushes heartbeats while legacy publication bytes stay unchanged`() {
        // Given
        withServer { baseUrl, configuration ->
            val body = checkNotNull(javaClass.getResource("/legacy-http/send.json")).readText()
            val transaction = LegacyHttpFixtures.transaction("send")
            val subscriptions = AtomicInteger()
            coEvery { configuration.publication.publishAndStream(any(), any(), any()) } returns
                flow {
                    subscriptions.incrementAndGet()
                    delay(1200)
                    emit(transaction)
                }
            HttpClient.newHttpClient().use { client ->
                fun publish(path: String): HttpResponse<String> =
                    client.send(
                        HttpRequest
                            .newBuilder(URI("$baseUrl$path"))
                            .timeout(Duration.ofSeconds(5))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                        HttpResponse.BodyHandlers.ofString(),
                    )

                // When
                val canonical = publish("/v2/transactions")
                val legacy = publish("/transactions/stream")
                val removed = publish("/v2/transactions/stream")

                // Then
                assertEquals(200, canonical.statusCode())
                assertTrue(canonical.body().startsWith("\n"), canonical.body())
                assertFalse(canonical.body().contains("publicKey", ignoreCase = true))
                assertEquals(200, legacy.statusCode())
                assertTrue(legacy.body().startsWith("{"), legacy.body())
                assertEquals(Json.parseToJsonElement(body), Json.parseToJsonElement(legacy.body().trim()))
                assertEquals(405, removed.statusCode())
                assertEquals(405, get(client, "$baseUrl/v2/transactions").statusCode())
                assertEquals(2, subscriptions.get())
            }
        }
    }

    @Test
    fun `finite account batches preserve canonical and legacy response contracts`() {
        // Given
        withServer { baseUrl, configuration ->
            val account = LegacyHttpFixtures.account
            val search = "{\"addresses\":[\"${account.address}\"]}"
            every { configuration.accounts.get(any<AccountSearch>()) } returns flowOf(account)
            HttpClient.newHttpClient().use { client ->
                // When
                val canonical = post(client, "$baseUrl/v2/accounts", search)
                val legacy = post(client, "$baseUrl/accounts", search)
                every { configuration.accounts.get(any<AccountSearch>()) } returns emptyFlow()
                val emptyCanonical = post(client, "$baseUrl/v2/accounts", "{\"addresses\":[]}")
                val emptyLegacy = post(client, "$baseUrl/accounts", "{\"addresses\":[]}")

                // Then
                assertEquals(200, canonical.statusCode())
                assertTrue(
                    canonical
                        .headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .startsWith("application/json"),
                )
                assertEquals(JsonArray(listOf(Json.encodeToJsonElement(account))), Json.parseToJsonElement(canonical.body()))
                assertFalse(canonical.body().contains("publicKey", ignoreCase = true))
                assertEquals(200, legacy.statusCode())
                val oldRecord = checkNotNull(javaClass.getResource("/legacy-http/account.json")).readText()
                assertEquals("[$oldRecord]", legacy.body())
                assertEquals(200, emptyCanonical.statusCode())
                assertEquals("[]", emptyCanonical.body())
                assertEquals(200, emptyLegacy.statusCode())
                assertEquals("[]", emptyLegacy.body())
            }
        }
    }

    @Test
    fun `legacy publication accepts key-only blocks while v2 requires addresses`() {
        // Given
        withServer { baseUrl, configuration ->
            HttpClient.newHttpClient().use { client ->
                for (name in listOf("open", "receive", "send", "change")) {
                    val oldBody = checkNotNull(javaClass.getResource("/legacy-http/$name.json")).readText()
                    val oldRecord = Json.parseToJsonElement(oldBody).jsonObject
                    val block = oldRecord.getValue("block").jsonObject
                    val keysOnly =
                        JsonObject(
                            oldRecord + (
                                "block" to
                                    JsonObject(
                                        block.filterKeys {
                                            it !in setOf("address", "receiverAddress", "representativeAddress") &&
                                                (name != "open" || it != "height")
                                        },
                                    )
                            ),
                        )
                    val transaction = LegacyHttpFixtures.transaction(name)
                    coEvery { configuration.publication.publish(any(), any(), any()) } returns transaction
                    coEvery { configuration.publication.publishAndStream(any(), any(), any()) } returns flowOf(transaction)

                    // When
                    val scalar = post(client, "$baseUrl/transactions", keysOnly.toString())
                    val streamed = post(client, "$baseUrl/transactions/stream", keysOnly.toString())
                    val rejected = post(client, "$baseUrl/v2/transactions", keysOnly.toString())
                    val canonical = post(client, "$baseUrl/v2/transactions", Json.encodeToJsonElement(transaction).toString())

                    // Then
                    assertEquals(200, scalar.statusCode(), name + scalar.body())
                    assertEquals(oldBody, scalar.body(), name)
                    assertEquals(200, streamed.statusCode(), name + streamed.body())
                    assertEquals(oldBody, streamed.body().trim(), name)
                    assertEquals(400, rejected.statusCode(), name + rejected.body())
                    assertEquals(200, canonical.statusCode(), name + canonical.body())
                    assertEquals(Json.encodeToJsonElement(transaction), Json.parseToJsonElement(canonical.body().trim()), name)
                    val role =
                        when (name) {
                            "open", "change" -> "representativeAddress"
                            "send" -> "receiverAddress"
                            else -> null
                        }
                    if (role != null) {
                        val missingRole = JsonObject(oldRecord + ("block" to JsonObject(block - role)))
                        assertEquals(400, post(client, "$baseUrl/v2/transactions", missingRole.toString()).statusCode(), name)
                    }
                }
            }
        }
    }

    private fun post(
        client: HttpClient,
        url: String,
        body: String,
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI(url))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun saveEvidence(
        name: String,
        body: String,
    ) {
        val directory = System.getenv("ATTO_ADDRESS_EVIDENCE_DIR") ?: return
        Files.writeString(Files.createDirectories(Path.of(directory)).resolve(name), body)
    }

    private fun get(
        client: HttpClient,
        url: String,
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun withServer(test: (String, RoutesConfiguration) -> Unit) {
        AnnotationConfigReactiveWebApplicationContext().use { context ->
            context.register(RoutesConfiguration::class.java)
            context.refresh()
            val server = NettyReactiveWebServerFactory(0).getWebServer(WebHttpHandlerBuilder.applicationContext(context).build())
            server.start()
            try {
                test("http://127.0.0.1:${server.port}", context.getBean(RoutesConfiguration::class.java))
            } finally {
                server.stop()
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebFlux
    @Import(
        AttoConversionsAutoConfiguration::class,
        SpringDocConfiguration::class,
        SpringDocKotlinxConfiguration::class,
        SpringDocWebFluxConfiguration::class,
        SpringDocConfigProperties::class,
        AttoSpringDocOpenApiAutoConfiguration::class,
    )
    class RoutesConfiguration : WebFluxConfigurer {
        override fun configureHttpMessageCodecs(configurer: ServerCodecConfigurer) {
            HttpSerializationConfiguration().configureHttpMessageCodecs(configurer)
        }

        val accounts =
            spyk(AccountController(AccountQueries(mockk(), mockk()))).apply {
                coEvery { get(any<AttoPublicKey>()) } returns ResponseEntity.notFound().build()
                coEvery { stream(any<AttoPublicKey>()) } returns emptyFlow()
            }
        val receivables =
            spyk(ReceivableController(ReceivableQueries(mockk()))).apply {
                coEvery { stream(any<AttoPublicKey>(), any()) } returns
                    emptyFlow()
            }
        val entries =
            spyk(AccountEntryController(AccountEntryQueries(mockk()))).apply {
                coEvery { stream(any<AttoPublicKey>(), any(), any()) } returns
                    emptyFlow()
            }
        val transactions =
            spyk(TransactionController(TransactionQueries(mockk()))).apply {
                coEvery { stream(any<AttoPublicKey>(), any(), any()) } returns
                    emptyFlow()
            }

        val publication = spyk(TransactionPublicationController(mockk(), mockk(), mockk(), mockk()))

        @Bean
        fun publicationController(): TransactionPublicationController = publication

        @Bean
        fun accountController(): AccountController = accounts

        @Bean
        fun receivableController(): ReceivableController = receivables

        @Bean
        fun entryController(): AccountEntryController = entries

        @Bean
        fun transactionController(): TransactionController = transactions
    }
}
