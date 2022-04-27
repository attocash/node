package org.atto.commons

import java.util.stream.Stream
import kotlin.random.Random

object AttoWorks {

    /**
     * This is super slow. Don't use it for live network!!!
     */
    fun work(hash: AttoHash, network: AttoNetwork): AttoWork {
        return work(hash.value, network)
    }

    internal fun work(hash: ByteArray, network: AttoNetwork): AttoWork {
        val controller = WorkerController();
        return Stream.generate { Worker(controller) }
            .takeWhile { controller.running }
            .parallel()
            .flatMap { it.work(hash, network) }
            .findAny()
            .map { AttoWork(it) }
            .get()
    }

    fun isValid(hash: AttoHash, work: AttoWork, network: AttoNetwork): Boolean {
        return isValid(hash.value, work.value, network)
    }

    internal fun isValid(hash: ByteArray, work: ByteArray, network: AttoNetwork): Boolean {
        val difficult = AttoHashes.hash(8, work, hash).toULong()
        return difficult >= network.threshold
    }

    private class WorkerController {
        var running = true
    }

    private class Worker(val controller: WorkerController) {
        private val work = ByteArray(8)

        fun work(hash: ByteArray, network: AttoNetwork): Stream<ByteArray> {
            while (controller.running) {
                Random.nextBytes(work)
                for (i in work.indices) {
                    val byte = work[i]
                    for (b in -128..126) {
                        work[i] = b.toByte()
                        if (isValid(hash, work, network)) {
                            controller.running = false
                            return Stream.of(work)
                        }
                    }
                    work[i] = byte
                }
            }
            return Stream.empty()
        }
    }
}

data class AttoWork(val value: ByteArray) {
    companion object {
        val size = 8

        fun work(hash: AttoHash, network: AttoNetwork): AttoWork {
            return AttoWorks.work(hash, network)
        }

        fun work(publicKey: AttoPublicKey, network: AttoNetwork): AttoWork {
            return AttoWorks.work(publicKey.value, network)
        }

        fun parse(value: String): AttoWork {
            return AttoWork(value.fromHexToByteArray())
        }
    }

    init {
        value.checkLength(size)
    }

    fun isValid(byteArray: ByteArray, network: AttoNetwork): Boolean {
        return AttoWorks.isValid(byteArray, value, network)
    }

    fun isValid(hash: AttoHash, network: AttoNetwork): Boolean {
        return AttoWorks.isValid(hash, this, network)
    }

    fun isValid(publicKey: AttoPublicKey, network: AttoNetwork): Boolean {
        return AttoWorks.isValid(publicKey.value, value, network)
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