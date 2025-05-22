package cash.atto.node.network

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PostConstruct
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

@Component
class NetworkMetricProvider(
    private val meterRegistry: MeterRegistry,
) {
    private val peerCount = AtomicInteger(0)
    private val counters = ConcurrentHashMap<Pair<String, String>, Counter>()

    @PostConstruct
    fun start() {
        Gauge
            .builder("network.peers.active", peerCount) { it.get().toDouble() }
            .description("Current number of active connected peers")
            .register(meterRegistry)
    }

    @EventListener
    fun add(nodeEvent: NodeConnected) {
        peerCount.incrementAndGet()
    }

    @EventListener
    fun remove(nodeEvent: NodeDisconnected) {
        peerCount.decrementAndGet()
    }

    @EventListener
    fun process(message: NetworkMessage<*>) {
        val type = message.javaClass.simpleName
        val payloadType = message.payload.javaClass.simpleName

        val key = Pair(type, payloadType)

        val counter =
            counters.computeIfAbsent(key) {
                Counter
                    .builder("network.messages.count")
                    .description("Count of network messages processed")
                    .tag("type", type)
                    .tag("payload", payloadType)
                    .register(meterRegistry)
            }

        counter.increment()
    }
}
