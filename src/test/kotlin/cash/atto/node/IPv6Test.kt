package cash.atto.node

import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioDatagramChannel
import io.netty.resolver.dns.DnsNameResolverBuilder
import org.junit.jupiter.api.Assertions
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

        @JvmStatic
        fun hosts(): Stream<String> = Stream.of(ipv4Host, ipv6Host)
    }

    @ParameterizedTest
    @MethodSource("hosts")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `inet address resolve`(host: String) {
        val addresses = InetAddress.getAllByName(host)
        Assertions.assertEquals(1, addresses.size)
    }

    @ParameterizedTest
    @MethodSource("hosts")
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    fun `netty should resolve`(host: String) {
        val eventLoopGroup = NioEventLoopGroup()
        val resolver =
            DnsNameResolverBuilder(eventLoopGroup.next())
                .channelType(NioDatagramChannel::class.java)
                .build()

        // Attempt to resolve an invalid domain
        val addressFuture = resolver.resolve(ipv6Host)

        addressFuture.get()
    }
}
