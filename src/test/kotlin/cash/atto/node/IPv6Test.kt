package cash.atto.node

import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.channel.socket.nio.NioSocketChannel
import io.netty.resolver.dns.DnsNameResolverBuilder
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.util.stream.Stream

/**
 * This test is just to hint graalvm to also include ipv6 classes
 */
class IPv6Test {
    companion object {
        private val ipv6Host = "ipv6.google.com"
        private val ipv4Host = "ipv4.google.com"

        val eventLoopGroup = NioEventLoopGroup()

        @AfterAll
        @JvmStatic
        fun afterAll() {
            eventLoopGroup.close()
        }

        @JvmStatic
        fun hosts(): Stream<String> = Stream.of(ipv4Host, ipv6Host)
    }

    @ParameterizedTest
    @MethodSource("hosts")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `inet address resolve`(host: String) {
        val address = InetAddress.getByName(host)
        assertNotNull(address)
    }

    @ParameterizedTest
    @MethodSource("hosts")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `netty should resolve`(host: String) {
        val resolver =
            DnsNameResolverBuilder(eventLoopGroup.next())
                .datagramChannelType(NioDatagramChannel::class.java)
                .socketChannelType(NioSocketChannel::class.java, true)
                .build()

        try {
            val addressFuture = resolver.resolve(host)

            addressFuture.get()
        } catch (e: Exception) {
            // issue: https://github.com/netty/netty/issues/13660
            if (host != ipv6Host) {
                throw e
            }
        }
    }
}
