package org.atto.protocol.network

interface AttoMessage {
    companion object {
        const val size = 6
    }

    fun messageType(): AttoMessageType
}