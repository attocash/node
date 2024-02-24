package atto.node

import org.awaitility.Awaitility
import org.hamcrest.Matchers
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

object Waiter {
    var timeoutInSeconds = 30L

    init {
        val isGradle = System.getenv("GRADLE")?.toBoolean() ?: false

        if (!isGradle) {
            timeoutInSeconds = 600L
        }
    }

    fun <T> waitUntilNonNull(callable: Callable<T>): T {
        return Awaitility.await().atMost(timeoutInSeconds, TimeUnit.SECONDS)
            .with()
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(callable, Matchers.notNullValue())
    }

    fun waitUntilTrue(callable: Callable<Boolean>?) {
        Awaitility.await().atMost(timeoutInSeconds, TimeUnit.SECONDS)
            .with()
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(callable) { it == true }
    }
}