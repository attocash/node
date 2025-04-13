package cash.atto.node

import com.github.benmanes.caffeine.cache.Caffeine
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.timeout
import java.util.concurrent.ConcurrentMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

class FlowRegistry<K, V>(
    private val replay: Int = 0,
    private val expiration: Duration = 1.minutes,
) {
    private val allFlow = MutableSharedFlow<V>(replay = replay)

    private val flowCache: ConcurrentMap<K, MutableSharedFlow<V>> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(expiration.toJavaDuration())
            .build<K, MutableSharedFlow<V>>()
            .asMap()

    @OptIn(FlowPreview::class)
    fun createFlow(key: K): Flow<V> {
        val flow = MutableSharedFlow<V>(replay = replay)
        flowCache[key] = flow
        return flow
            .onCompletion {
                flowCache.remove(key)
            }
            .timeout(expiration)
    }

    /**
     * return false when there's no matching flow with given key
     */
    suspend fun tryEmit(
        key: K,
        value: V,
    ): Boolean {
        allFlow.emit(value)
        val flow = flowCache[key] ?: return false
        flow.emit(value)
        return true
    }

    fun streamAll(): Flow<V> = allFlow

    fun clear() {
        flowCache.clear()
    }
}
