package org.atto.protocol.network.codec

import org.atto.commons.AttoByteBuffer

interface Codec<T> {

    fun fromByteBuffer(byteBuffer: AttoByteBuffer): T?

    fun toByteBuffer(t: T): AttoByteBuffer

}