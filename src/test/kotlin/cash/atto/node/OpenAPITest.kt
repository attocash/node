package cash.atto.node

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["management.server.port=0", "websocket.port=0"],
)
@AutoConfigureWebTestClient(timeout = "PT60S")
class OpenAPITest {
    @Autowired
    lateinit var webTestClient: WebTestClient

    @LocalManagementPort
    var managementPort: Int = 0

    @Test
    fun `OpenAPI exposes transaction query and publication endpoints`() {
        // Given
        val request = webTestClient.get().uri("/v3/api-docs")

        // When
        val response = request.exchange()

        // Then
        response
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.paths['/transactions'].post.responses['200']")
            .exists()
            .jsonPath("$.paths['/v2/transactions'].post.responses['200']")
            .exists()
            .jsonPath("$.paths['/v2/transactions/stream'].get.responses['200']")
            .exists()
            .jsonPath("$.paths['/transactions/{hash}'].get.responses['200']")
            .exists()
    }

    @Test
    fun `address based v2 routes replace public key routes in generated OpenAPI`() {
        // Given
        val request = webTestClient.get().uri("/v3/api-docs")

        // When
        val response = request.exchange()

        // Then
        response
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.paths['/accounts/{publicKey}']")
            .doesNotExist()
            .jsonPath("$.paths['/accounts/{publicKey}/stream']")
            .doesNotExist()
            .jsonPath("$.paths['/accounts/{publicKey}/receivables/stream']")
            .doesNotExist()
            .jsonPath("$.paths['/accounts/{publicKey}/entries/stream']")
            .doesNotExist()
            .jsonPath("$.paths['/accounts/{publicKey}/transactions/stream']")
            .doesNotExist()
            .jsonPath("$.paths['/v2/accounts/{address}']")
            .exists()
            .jsonPath("$.paths['/v2/accounts/{address}/stream']")
            .exists()
            .jsonPath("$.paths['/v2/accounts/{address}/receivables/stream']")
            .exists()
            .jsonPath("$.paths['/v2/accounts/{address}/entries/stream']")
            .exists()
            .jsonPath("$.paths['/v2/accounts/{address}/transactions/stream']")
            .exists()
    }

    @Test
    fun `production operations have descriptive and unique IDs without test helper endpoints`() {
        // Given
        val request = webTestClient.get().uri("/v3/api-docs")

        // When
        val body =
            request
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody
        val paths =
            Json
                .parseToJsonElement(checkNotNull(body))
                .jsonObject
                .getValue("paths")
                .jsonObject

        // Then
        val expectedOperations =
            listOf(
                Triple("/accounts", "post", "getLegacyAccountsByAddresses"),
                Triple("/v2/accounts", "post", "getAccountsByAddresses"),
                Triple("/v2/accounts/{address}", "get", "getAccountByAddress"),
                Triple("/v2/accounts/stream", "get", "streamAllAccounts"),
                Triple("/v2/accounts/stream", "post", "streamAccountsByAddresses"),
                Triple("/v2/accounts/{address}/stream", "get", "streamAccountByAddress"),
                Triple("/v2/accounts/receivables/stream", "post", "streamReceivablesByAddresses"),
                Triple("/v2/accounts/{address}/receivables/stream", "get", "streamReceivablesByAddress"),
                Triple("/v2/accounts/entries/stream", "get", "streamAllAccountEntries"),
                Triple("/v2/accounts/entries/stream", "post", "streamAccountEntriesByHeightRanges"),
                Triple("/v2/accounts/entries/{hash}/stream", "get", "streamAccountEntryByHash"),
                Triple("/v2/accounts/{address}/entries/stream", "get", "streamAccountEntriesByAddress"),
                Triple("/v2/accounts/transactions/stream", "post", "streamTransactionsByHeightRanges"),
                Triple("/v2/accounts/{address}/transactions/stream", "get", "streamTransactionsByAddress"),
                Triple("/v2/transactions/stream", "get", "streamAllTransactions"),
                Triple("/v2/transactions/{hash}/stream", "get", "streamTransactionByHash"),
                Triple("/v2/transactions", "post", "publishTransactionAndWaitForConfirmation"),
                Triple("/transactions", "post", "publishLegacyTransactionAndWaitForConfirmation"),
                Triple("/transactions/{hash}", "get", "getTransactionByHash"),
                Triple("/vote-weights/{address}", "get", "getVoterWeightByAddress"),
                Triple("/instants/{clientInstant}", "get", "getTimeDifference"),
            )
        for ((path, method, operationId) in expectedOperations) {
            assertEquals(
                operationId,
                paths
                    .getValue(path)
                    .jsonObject
                    .getValue(method)
                    .jsonObject
                    .getValue("operationId")
                    .jsonPrimitive.content,
            )
        }
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
        assertEquals("array", batchSchema.getValue("type").jsonPrimitive.content)
        assertEquals(
            "#/components/schemas/AttoAccount",
            batchSchema
                .getValue("items")
                .jsonObject
                .getValue("\$ref")
                .jsonPrimitive.content,
        )
        val methods = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
        val operationIds =
            paths.values.flatMap { path ->
                path.jsonObject
                    .filterKeys { it in methods }
                    .values
                    .map {
                        it.jsonObject
                            .getValue("operationId")
                            .jsonPrimitive.content
                    }
            }
        assertEquals(operationIds.size, operationIds.toSet().size, operationIds.toString())
        assertEquals(expectedOperations.map { it.third }.toSet(), operationIds.toSet())
        for (path in listOf(
            "/nodes/peers",
            "/nodes/bootstrap",
            "/unchecked-transactions",
            "/unchecked-transactions/discoveries/gap",
            "/unchecked-transactions/discoveries/last",
            "/unchecked-transactions/discoveries/flush",
            "/unchecked-transactions/discoveries/settle",
        )) {
            assertFalse(paths.containsKey(path), path)
        }
    }

    @Test
    fun `GET health probes are reachable`() {
        // Given
        val managementWebTestClient =
            WebTestClient
                .bindToServer()
                .baseUrl("http://127.0.0.1:$managementPort")
                .build()

        // When
        val liveness = managementWebTestClient.get().uri("/health/liveness").exchange()
        val readiness = managementWebTestClient.get().uri("/health/readiness").exchange()

        // Then
        liveness
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("UP")
        readiness
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("UP")
    }
}
