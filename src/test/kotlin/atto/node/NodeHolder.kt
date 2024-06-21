package atto.node

import java.io.Closeable
import java.util.*

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
            .forEach { it.close() }
        nodes.clear()
    }
}
