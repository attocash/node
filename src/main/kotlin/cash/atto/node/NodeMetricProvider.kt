package cash.atto.node

import cash.atto.commons.AttoAddress
import cash.atto.protocol.AttoNode
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import jakarta.annotation.PostConstruct
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

@Component
class NodeMetricProvider(
    private val thisNode: AttoNode,
    @param:Value("\${spring.application.version:local}") private val applicationVersion: String,
    private val meterRegistry: MeterRegistry,
) {
    @PostConstruct
    fun start() {
        val version = applicationVersion.substringBefore('-').toDoubleOrNull() ?: 0.0
        Gauge
            .builder("node.version", this) { version }
            .description("Information about the Atto node")
            .tags(
                listOf(
                    Tag.of("public_uri", thisNode.publicUri.toString()),
                    Tag.of("network", thisNode.network.toString()),
                    Tag.of("public_key", thisNode.publicKey.toString()),
                    Tag.of("algorithm", thisNode.algorithm.toString()),
                    Tag.of("address", AttoAddress(thisNode.algorithm, thisNode.publicKey).toString()),
                    Tag.of("features", thisNode.features.joinToString(", ")),
                    Tag.of("version", version.toString()),
                ),
            ).register(meterRegistry)
    }
}
