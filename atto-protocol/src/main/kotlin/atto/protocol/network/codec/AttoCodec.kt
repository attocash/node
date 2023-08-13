package atto.protocol.network.codec

import cash.atto.commons.AttoByteBuffer

interface AttoCodec<T> {

    fun fromByteBuffer(byteBuffer: AttoByteBuffer): T?

    fun toByteBuffer(t: T): AttoByteBuffer

}