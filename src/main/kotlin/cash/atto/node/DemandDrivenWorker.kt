package cash.atto.node

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

internal class DemandDrivenWorker(
    name: String,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val drain: suspend () -> Unit,
) {
    private val logger = KotlinLogging.logger {}
    private val scope = CoroutineScope(dispatcher + SupervisorJob() + CoroutineName(name))
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val job: Job =
        scope.launch(CoroutineName(name), start = CoroutineStart.LAZY) {
            var nextDelay = COALESCING_WINDOW

            while (true) {
                requests.receive()
                delay(nextDelay)

                do {
                    try {
                        drain()
                        nextDelay = COALESCING_WINDOW
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        nextDelay = (nextDelay * 2).coerceAtMost(MAX_BACKOFF)
                        logger.warn(e) { "$name failed. Retrying queued work in $nextDelay" }
                        request()
                        break
                    }
                } while (requests.tryReceive().isSuccess)
            }
        }

    fun request() {
        requests.trySend(Unit)
        job.start()
    }

    fun cancel() {
        scope.cancel()
    }

    private companion object {
        val COALESCING_WINDOW = 1.milliseconds
        val MAX_BACKOFF = 1.minutes
    }
}
