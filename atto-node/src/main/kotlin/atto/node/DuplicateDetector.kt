package atto.node

import com.github.benmanes.caffeine.cache.Caffeine

class DuplicateDetector<T> {
    private val cache: MutableMap<T, T> = Caffeine.newBuilder()
        .maximumSize(1_000_000)
        .build<T, T>()
        .asMap()

    fun isDuplicate(t: T): Boolean {
        return cache.putIfAbsent(t, t) != null
    }

    fun clear() {
        cache.clear()
    }
}