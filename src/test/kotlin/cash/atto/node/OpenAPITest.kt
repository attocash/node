package cash.atto.node

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.web.reactive.server.WebTestClient

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["websocket.port=0"],
)
class OpenAPITest {
    @Autowired
    lateinit var webTestClient: WebTestClient

    @Test
    fun `GET OpenAPI docs are reachable`() {
        webTestClient
            .get()
            .uri("/v3/api-docs")
            .exchange()
            .expectStatus()
            .isOk
    }
}
