package cash.atto.node.transaction

import cash.atto.commons.AttoAlgorithm
import cash.atto.commons.AttoAmount
import cash.atto.commons.AttoHash
import cash.atto.commons.AttoInstant
import cash.atto.commons.AttoNetwork
import cash.atto.commons.AttoPublicKey
import cash.atto.commons.AttoReceiveBlock
import cash.atto.commons.AttoSignature
import cash.atto.commons.AttoWork
import cash.atto.commons.toAttoHeight
import cash.atto.commons.toAttoVersion
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

class TransactionMetricProviderTest {
    @Test
    fun `should expose startup zeros and record the first pipeline observation`() {
        // Given
        val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)
        val provider = TransactionMetricProvider(registry)
        val receivedAt = Instant.parse("2026-09-15T11:00:00Z")
        val transaction = transaction(receivedAt)

        // When
        val startupScrape = registry.scrape()
        val initialCounts = registry.find("transactions.pipeline.latency").timers().map { it.count() }
        provider.process(TransactionReceived(transaction, timestamp = receivedAt.plusMillis(250)))
        val observationScrape = registry.scrape()

        // Then
        assertEquals(28, initialCounts.size)
        assertTrue(initialCounts.all { it == 0L })
        assertEquals(28, startupScrape.lineSequence().count { it.startsWith("transactions_pipeline_latency_seconds_count{") })
        assertEquals(28, observationScrape.lineSequence().count { it.startsWith("transactions_pipeline_latency_seconds_count{") })
        val timers = registry.find("transactions.pipeline.latency").timers()
        assertEquals(setOf("OPEN", "RECEIVE", "SEND", "CHANGE"), timers.map { it.id.getTag("type") }.toSet())
        timers.forEach { timer ->
            assertEquals(
                setOf("stage", "type"),
                timer.id.tags
                    .map { it.key }
                    .toSet(),
            )
        }
        val timer =
            registry
                .find("transactions.pipeline.latency")
                .tag("stage", "received")
                .tag("type", "RECEIVE")
                .timer()
        assertNotNull(timer)
        assertEquals(1, timer!!.count())
        assertEquals(250.0, timer.totalTime(TimeUnit.MILLISECONDS), 0.000001)
        assertEquals(1, timers.sumOf { it.count() })
    }

    private fun transaction(receivedAt: Instant): Transaction =
        Transaction(
            block =
                AttoReceiveBlock(
                    version = 0U.toAttoVersion(),
                    network = AttoNetwork.LOCAL,
                    algorithm = AttoAlgorithm.V1,
                    publicKey = AttoPublicKey(ByteArray(32)),
                    height = 2U.toAttoHeight(),
                    balance = AttoAmount.MAX,
                    timestamp = AttoInstant.now(),
                    previous = AttoHash(ByteArray(32)),
                    sendHashAlgorithm = AttoAlgorithm.V1,
                    sendHash = AttoHash(ByteArray(32)),
                ),
            signature = AttoSignature(ByteArray(64)),
            work = AttoWork(ByteArray(8)),
            receivedAt = receivedAt,
        )
}
