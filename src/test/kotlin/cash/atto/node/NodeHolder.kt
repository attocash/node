package cash.atto.node

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.net.URLClassLoader

object NodeHolder {
    private val logger = KotlinLogging.logger {}

    private const val THREAD_SHUTDOWN_TIMEOUT_IN_MILLIS = 1_000L

    private val nodes = HashSet<NodeContext>()

    fun add(
        context: Closeable,
        classLoader: ClassLoader? = context.javaClass.classLoader,
    ) {
        nodes.add(NodeContext(context, classLoader))
    }

    fun clear(except: Closeable) {
        val toClose = nodes.filter { it.context != except }

        toClose.forEach { it.context.close() }

        toClose.forEach { node ->
            val classLoader = node.classLoader as? URLClassLoader ?: return@forEach
            val remaining = waitForThreads(classLoader)

            if (remaining.isEmpty()) {
                classLoader.close()
                return@forEach
            }

            Thread
                .getAllStackTraces()
                .keys
                .filter { it.contextClassLoader == classLoader && it.isAlive && it.isDaemon }
                .forEach { it.contextClassLoader = null }

            logger.warn { "Leaving classloader open with ${remaining.size} threads still alive: ${remaining.map { it.name }}" }
        }

        nodes.removeAll(toClose.toSet())
    }

    private fun waitForThreads(classLoader: ClassLoader): List<Thread> {
        val deadline = System.currentTimeMillis() + THREAD_SHUTDOWN_TIMEOUT_IN_MILLIS
        var remaining = threadsFor(classLoader)

        while (remaining.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            remaining = threadsFor(classLoader)
        }

        return remaining
    }

    private fun threadsFor(classLoader: ClassLoader): List<Thread> =
        Thread
            .getAllStackTraces()
            .keys
            .filter { it.contextClassLoader == classLoader && it.isAlive }

    private data class NodeContext(
        val context: Closeable,
        val classLoader: ClassLoader?,
    )
}
