package cash.atto.node

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.net.InetAddress

class IPv6Test {

    /**
     * This test is just to hint graalvm to also include ipv6 classes
     */
    @Test
    fun shouldAllowIPV6() {
        val addresses = InetAddress.getAllByName("google.com")
        Assertions.assertEquals(2, addresses.size)
    }
}
