package org.atto.protocol

import org.atto.commons.toByteArray
import org.atto.commons.toByteBuffer
import org.atto.commons.toUShort
import java.net.InetAddress
import java.net.InetSocketAddress


fun UShort.toByteArray(): ByteArray {
    return this.toByteBuffer().array()
}

fun InetSocketAddress.toByteArray(): ByteArray {
    val address = this.address.address
    val port = this.port.toUShort().toByteArray() // port is int but when serialized is 2 bytes

    val byteArray = ByteArray(18)
    if (address.size == 16) {
        System.arraycopy(address, 0, byteArray, 0, 16)
    } else {
        byteArray[10] = -1
        byteArray[11] = -1
        System.arraycopy(address, 0, byteArray, 12, 4)
    }

    System.arraycopy(port, 0, byteArray, 16, 2)

    return byteArray
}


fun ByteArray.toInetSocketAddress(): InetSocketAddress {
    val address = InetAddress.getByAddress(this.sliceArray(0 until 16))
    val port = this.sliceArray(16 until 18).toUShort().toInt()

    return InetSocketAddress(address, port)
}