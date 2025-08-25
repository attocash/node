package cash.atto.node

import org.awaitility.Awaitility
import org.hamcrest.Matchers
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

object Waiter {
    val timeoutInSeconds = 60L // CHANGE ME DURING TESTS

    fun <T> waitUntilNonNull(callable: Callable<T>): T =
        Awaitility
            .await()
            .atMost(timeoutInSeconds, TimeUnit.SECONDS)
            .with()
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(callable, Matchers.notNullValue())

    fun waitUntilTrue(callable: Callable<Boolean>?) {
        Awaitility
            .await()
            .atMost(timeoutInSeconds, TimeUnit.SECONDS)
            .with()
            .pollInterval(100, TimeUnit.MILLISECONDS)
            .until(callable) { it == true }
    }
}
