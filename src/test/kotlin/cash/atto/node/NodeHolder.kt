package cash.atto.node

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.Closeable
import java.net.URLClassLoader

object NodeHolder {
    private val logger = KotlinLogging.logger {}

    private val nodes = HashSet<Closeable>()

    fun add(context: Closeable) {
        nodes.add(context)
    }

    fun clear(except: Closeable) {
        val toClose = nodes.filter { it != except }

        toClose.forEach { it.close() }

        toClose.forEach { node ->
            val classLoader = node.javaClass.classLoader as? URLClassLoader ?: return@forEach
            val deadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < deadline) {
                val alive =
                    Thread.getAllStackTraces().keys.any {
                        it.contextClassLoader == classLoader && it.isAlive && !it.isDaemon
                    }
                if (!alive) break
                Thread.sleep(100)
            }
            // Clear classloader reference from daemon threads (shared pool threads) to allow GC
            Thread
                .getAllStackTraces()
                .keys
                .filter { it.contextClassLoader == classLoader && it.isAlive && it.isDaemon }
                .forEach { it.contextClassLoader = null }

            val remaining = Thread.getAllStackTraces().keys.filter { it.contextClassLoader == classLoader && it.isAlive }
            if (remaining.isNotEmpty()) {
                logger.warn { "Closing classloader with ${remaining.size} non-daemon threads still alive: ${remaining.map { it.name }}" }
            }
            classLoader.close()
        }

        nodes.clear()
    }
}
