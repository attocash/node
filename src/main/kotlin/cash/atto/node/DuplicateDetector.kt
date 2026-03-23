package cash.atto.node

import com.github.benmanes.caffeine.cache.Caffeine
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class DuplicateDetector<T : Any>(
    val duration: Duration,
) {
    private val cache: MutableMap<T, T> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(duration.toJavaDuration())
            .build<T, T>()
            .asMap()

    fun isDuplicate(t: T): Boolean = cache.putIfAbsent(t, t) != null

    fun remove(t: T) {
        cache.remove(t)
    }

    fun clear() {
        cache.clear()
    }
}
