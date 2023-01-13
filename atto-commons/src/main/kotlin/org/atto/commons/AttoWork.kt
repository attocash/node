package org.atto.commons

import java.time.Instant
import java.util.stream.Stream
import kotlin.random.Random

private fun isValid(network: AttoNetwork, timestamp: Instant, hash: ByteArray, work: ByteArray): Boolean {
    val difficult = AttoHash.hash(8, work, hash).value.toULong()
    return difficult >= network.getThreshold(timestamp)
}

private class WorkerController {
    private var work: AttoWork? = null

    fun isEmpty(): Boolean {
        return work == null
    }

    fun add(work: ByteArray) {
        this.work = AttoWork(work)
    }

    fun get(): AttoWork? {
        return work
    }
}

private class Worker(
    val controller: WorkerController,
    val network: AttoNetwork,
    val timestamp: Instant,
    val hash: ByteArray
) {
    private val work = ByteArray(8)

    fun work() {
        while (controller.isEmpty()) {
            Random.nextBytes(work)
            for (i in work.indices) {
                val byte = work[i]
                for (b in -128..126) {
                    work[i] = b.toByte()
                    if (isValid(network, timestamp, hash, work)) {
                        controller.add(work)
                        return
                    }
                }
                work[i] = byte
            }
        }
    }
}

data class AttoWork(val value: ByteArray) {
    companion object {
        const val size = 8

        fun isValid(network: AttoNetwork, timestamp: Instant, hash: AttoHash, work: AttoWork): Boolean {
            return isValid(network, timestamp, hash.value, work.value)
        }

        fun isValid(network: AttoNetwork, timestamp: Instant, publicKey: AttoPublicKey, work: AttoWork): Boolean {
            return isValid(network, timestamp, publicKey.value, work.value)
        }

        fun work(network: AttoNetwork, timestamp: Instant, hash: AttoHash): AttoWork {
            return work(network, timestamp, hash.value)
        }

        fun work(network: AttoNetwork, timestamp: Instant, publicKey: AttoPublicKey): AttoWork {
            return work(network, timestamp, publicKey.value)
        }

        private fun work(network: AttoNetwork, timestamp: Instant, hash: ByteArray): AttoWork {
            val controller = WorkerController()
            return Stream.generate { Worker(controller, network, timestamp, hash) }
                .takeWhile { controller.isEmpty() }
                .parallel()
                .peek { it.work() }
                .map { controller.get() }
                .filter { it != null }
                .findAny()
                .get()
        }

        fun parse(value: String): AttoWork {
            return AttoWork(value.fromHexToByteArray())
        }
    }

    init {
        value.checkLength(size)
    }

    fun isValid(network: AttoNetwork, timestamp: Instant, publicKey: AttoPublicKey): Boolean {
        return isValid(network, timestamp, publicKey, this)
    }

    fun isValid(network: AttoNetwork, timestamp: Instant, hash: AttoHash): Boolean {
        return isValid(network, timestamp, hash, this)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AttoWork

        if (!value.contentEquals(other.value)) return false

        return true
    }

    override fun hashCode(): Int {
        return value.contentHashCode()
    }

    override fun toString(): String {
        return value.toHex().lowercase()
    }
}