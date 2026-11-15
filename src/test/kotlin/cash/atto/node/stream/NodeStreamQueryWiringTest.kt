package cash.atto.node.stream

import cash.atto.commons.node.AttoStreamMethod
import cash.atto.commons.node.AttoStreamRequest
import cash.atto.node.HttpSerializationConfiguration
import cash.atto.node.account.LegacyHttpFixtures
import cash.atto.node.account.entry.AccountEntryController
import cash.atto.node.account.entry.AccountEntryQueries
import cash.atto.node.account.entry.AccountEntryRepository
import cash.atto.node.account.entry.AccountEntrySaved
import cash.atto.node.account.entry.toEntity
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import

class NodeStreamQueryWiringTest {
    @Test
    fun `HTTP and WebSocket reads receive the same event from their shared query owner`(): Unit =
        runBlocking {
            // Given
            AnnotationConfigApplicationContext(TestConfiguration::class.java).use { context ->
                val queries = context.getBean(AccountEntryQueries::class.java)
                val controller = context.getBean(AccountEntryController::class.java)
                val json = HttpSerializationConfiguration().publicApiJson()
                val dispatcher = NodeStreamDispatcher(mockk(), mockk(), queries, mockk(), json)
                val entry = LegacyHttpFixtures.entry
                val http = async(start = CoroutineStart.UNDISPATCHED) { controller.stream().first() }
                val websocket =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        dispatcher.dispatch(AttoStreamRequest(AttoStreamMethod.GET, "/v2/accounts/entries/stream")).first()
                    }

                // When
                context.publishEvent(AccountEntrySaved(entry.toEntity()))

                // Then
                withTimeout(5_000) {
                    assertEquals(entry, http.await())
                    assertEquals(json.encodeToJsonElement(entry), websocket.await())
                }
            }
        }

    @Configuration(proxyBeanMethods = false)
    @Import(AccountEntryQueries::class, AccountEntryController::class)
    class TestConfiguration {
        @Bean
        fun entryRepository(): AccountEntryRepository = mockk()
    }
}
