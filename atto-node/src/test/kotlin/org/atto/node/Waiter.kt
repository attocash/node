package org.atto.node

import org.awaitility.Awaitility
import org.hamcrest.Matchers
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

object Waiter {
    var timeoutInSeconds = 60

    init {
        val isGradle = System.getenv("GRADLE")?.toBoolean() ?: false

        if (!isGradle) {
            timeoutInSeconds = 600
        }
    }

    fun <T> waitUntilNonNull(callable: Callable<T>?): T {
        return Awaitility.await().atMost(timeoutInSeconds.toLong(), TimeUnit.SECONDS)
            .with()
            .pollDelay(50, TimeUnit.MILLISECONDS)
            .until(callable, Matchers.notNullValue())
    }
}