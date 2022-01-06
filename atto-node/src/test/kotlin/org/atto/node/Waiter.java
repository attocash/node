package org.atto.node;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.notNullValue;

public class Waiter {
    public static int timeoutInSeconds = 50000;

    public static <T> T waitUntilNonNull(Callable<T> callable) {
        return await().atMost(timeoutInSeconds, TimeUnit.SECONDS)
                .with()
                .pollDelay(50, TimeUnit.MILLISECONDS)
                .until(callable, notNullValue());
    }
}