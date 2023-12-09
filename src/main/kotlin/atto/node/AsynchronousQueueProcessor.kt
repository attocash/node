package atto.node

import jakarta.annotation.PostConstruct
import kotlinx.coroutines.*
import mu.KotlinLogging
import kotlin.system.exitProcess
import kotlin.time.Duration


abstract class AsynchronousQueueProcessor<T>(private val duration: Duration) {
    private val logger = KotlinLogging.logger {}

    private lateinit var job: Job

    @OptIn(DelicateCoroutinesApi::class)
    @PostConstruct
    fun start() {
        job = GlobalScope.launch(CoroutineName(this.javaClass.simpleName)) {
            while (isActive) {
                try {
                    process()
                } catch (e: Exception) {
                    logger.error(e) { "Error occurred while processing queue. Application will exit" }
                    exitProcess(-1)
                }
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