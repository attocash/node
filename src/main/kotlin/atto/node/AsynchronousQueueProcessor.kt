package atto.node

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.*
import kotlin.time.Duration


abstract class AsynchronousQueueProcessor<T>(private val duration: Duration) {
    private lateinit var job: Job

    @PostConstruct
    fun start() {
        job = CoroutineScope(Dispatchers.Default + attoCoroutineExceptionHandler).launch {
            while (isActive) {
                process()
                delay(duration.inWholeMilliseconds)
            }
        }
    }

    fun cancel() {
        job.cancel()
    }

    protected abstract suspend fun poll(): T?

    protected abstract suspend fun process(value: T)

    private suspend fun process() {
        var value = poll()
        while (value != null) {
            process(value)
            value = poll()
        }
    }

}