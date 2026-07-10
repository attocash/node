package cash.atto.node

import com.github.benmanes.caffeine.cache.Caffeine
import kotlin.time.Duration
import kotlin.time.toJavaDuration

class DuplicateDetector<T : Any>(
    val duration: Duration,
    maximumSize: Long? = null,
) {
    class Reservation<T : Any> internal constructor(
        internal val value: T,
    )

    init {
        require(maximumSize == null || maximumSize > 0) { "maximumSize must be positive" }
    }

    private val cache =
        Caffeine
            .newBuilder()
            .expireAfterWrite(duration.toJavaDuration())
            .also { builder ->
                if (maximumSize != null) {
                    builder.maximumSize(maximumSize)
                }
            }.build<T, Reservation<T>>()

    private val entries = cache.asMap()

    val size: Int
        get() {
            cache.cleanUp()
            return cache.estimatedSize().toInt()
        }

    fun reserve(t: T): Reservation<T>? {
        val reservation = Reservation(t)
        return if (entries.putIfAbsent(t, reservation) == null) reservation else null
    }

    fun isDuplicate(t: T): Boolean = reserve(t) == null

    fun remove(reservation: Reservation<T>): Boolean = entries.remove(reservation.value, reservation)

    fun remove(t: T) {
        entries.remove(t)
    }

    fun clear() {
        entries.clear()
    }
}
