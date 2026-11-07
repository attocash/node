package cash.atto.node

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
            .jsonPath("$.paths['/transactions/stream'].post.responses['200']")
            .exists()
            .jsonPath("$.paths['/transactions/stream'].get.responses['200']")
            .exists()
            .jsonPath("$.paths['/transactions/{hash}'].get.responses['200']")
            .exists()
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
