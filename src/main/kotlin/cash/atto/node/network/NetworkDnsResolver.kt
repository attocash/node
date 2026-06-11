package cash.atto.node.network

import jakarta.annotation.PreDestroy
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import org.springframework.stereotype.Component
import java.net.InetAddress
import java.util.concurrent.Executors

@Component
class NetworkDnsResolver {
    private val dispatcher = Executors.newVirtualThreadPerTaskExecutor().asCoroutineDispatcher()

    suspend fun getByName(host: String): InetAddress =
        withContext(dispatcher) {
            InetAddress.getByName(host)
        }

    suspend fun getAllByName(host: String): List<InetAddress> =
        withContext(dispatcher) {
            InetAddress.getAllByName(host).toList()
        }

    @PreDestroy
    fun close() {
        dispatcher.close()
    }
}
