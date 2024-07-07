package cash.atto.node

import java.io.Closeable
import java.net.URLClassLoader
import java.util.Collections

object NodeHolder {
    private val nodes = ArrayList<Closeable>()

    fun add(context: Closeable) {
        nodes.add(context)
    }

    fun getAll(): List<Closeable> = Collections.unmodifiableList(nodes)

    fun clear(except: Closeable) {
        nodes
            .asSequence()
            .filter { it != except }
            .forEach {
                it.close()
                (it.javaClass.classLoader as URLClassLoader).close()
            }
        nodes.clear()
    }
}
