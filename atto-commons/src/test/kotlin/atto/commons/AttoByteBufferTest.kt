package atto.commons

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.random.Random

internal class AttoByteBufferTest {

    @Test
    fun test() {
        // given
        val size = 184
        val buffer = AttoByteBuffer(size)

        val expectedHash = AttoHash(Random.Default.nextBytes(ByteArray(32)))
        buffer.add(expectedHash)

        val expectedPublicKey = AttoPublicKey(Random.Default.nextBytes(ByteArray(32)))
        buffer.add(expectedPublicKey)

        val expectedUShort = UShort.MAX_VALUE
        buffer.add(expectedUShort)

        val expectedULong = ULong.MAX_VALUE
        buffer.add(expectedULong)

        val expectedInstant = Instant.ofEpochMilli(Instant.now().toEpochMilli())
        buffer.add(expectedInstant)

        val expectedBlockType = AttoBlockType.SEND
        buffer.add(expectedBlockType)

        val expectedAmount = AttoAmount.MAX
        buffer.add(expectedAmount)

        val expectedSignature = AttoSignature(Random.Default.nextBytes(ByteArray(64)))
        buffer.add(expectedSignature)

        val expectedWork = AttoWork(Random.Default.nextBytes(ByteArray(8)))
        buffer.add(expectedWork)

        val expectedInetSocketAddress = InetSocketAddress(InetAddress.getLocalHost(), 8330)
        buffer.add(expectedInetSocketAddress)

        val expectedNetwork = AttoNetwork.LOCAL
        buffer.add(expectedNetwork)

        // when
        val hash = buffer.getHash()
        val publicKey = buffer.getPublicKey()
        val uShort = buffer.getUShort()
        val uLong = buffer.getULong()
        val instant = buffer.getInstant()
        val blockType = buffer.getBlockType()
        val amount = buffer.getAmount()
        val signature = buffer.getSignature()
        val work = buffer.getWork()
        val inetSocketAddress = buffer.getInetSocketAddress()
        val network = buffer.getNetwork()

        // then
        assertEquals(expectedHash, hash)
        assertEquals(expectedPublicKey, publicKey)
        assertEquals(expectedUShort, uShort)
        assertEquals(expectedULong, uLong)
        assertEquals(expectedInstant, instant)
        assertEquals(expectedBlockType, blockType)
        assertEquals(expectedAmount, amount)
        assertEquals(expectedSignature, signature)
        assertEquals(expectedWork, work)
        assertEquals(expectedInetSocketAddress, inetSocketAddress)
        assertEquals(expectedNetwork, network)
        assertEquals(size, buffer.toByteArray().size)
    }

    @Test
    fun slice() {
        // given
        val buffer = AttoByteBuffer(6)
        buffer.add((1).toShort())
        buffer.add((2).toShort())
        buffer.add((3).toShort())

        // when
        val short = buffer.slice(4).getShort()

        // then
        assertEquals(3, short)
    }

    @Test
    fun toByteArray() {
        // given
        val buffer = AttoByteBuffer(2)
            .add((1u).toUByte())
            .add((2u).toUByte())
            .slice(1)

        buffer.getUByte()

        // when
        val byteArray = buffer.toByteArray()

        // then
        assertEquals(1, byteArray.size)
    }

}