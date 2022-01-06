package org.atto.protocol.network.codec

interface Codec<T> {

    fun fromByteArray(byteArray: ByteArray): T?

    fun toByteArray(t: T): ByteArray

}