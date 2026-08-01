package cash.atto.node.network

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI

class PeerUriValidatorTest {
    @Test
    fun `should return resolved addresses for globally routable peer uri`() =
        runTest {
            // given
            val publicAddress = address("93.184.216.34")
            val validator = validator(addresses = listOf(publicAddress))
            val publicUri = URI("ws://peer.example:7070")

            // when
            val result = validator.validate(publicUri)

            // then
            val accepted = assertInstanceOf(PeerUriValidationResult.Accepted::class.java, result)
            assertEquals(publicUri, accepted.endpoint.publicUri)
            assertEquals(listOf(publicAddress), accepted.endpoint.addresses)
        }

    @Test
    fun `should reject private peer uri`() =
        runTest {
            // given
            val validator = validator(addresses = listOf(address("10.0.0.1")))

            // when
            val result = validator.validate(URI("ws://peer.example:7070"))

            // then
            assertInstanceOf(PeerUriValidationResult.Rejected::class.java, result)
        }

    @Test
    fun `should reject metadata peer uri`() =
        runTest {
            // given
            val validator = validator(addresses = listOf(address("169.254.169.254")))

            // when
            val result = validator.validate(URI("ws://peer.example:80"))

            // then
            assertInstanceOf(PeerUriValidationResult.Rejected::class.java, result)
        }

    @Test
    fun `should reject peer uri when any resolved address is unsafe`() =
        runTest {
            // given
            val validator =
                validator(
                    addresses =
                        listOf(
                            address("93.184.216.34"),
                            address("127.0.0.1"),
                        ),
                )

            // when
            val result = validator.validate(URI("ws://peer.example:7070"))

            // then
            assertInstanceOf(PeerUriValidationResult.Rejected::class.java, result)
        }

    @Test
    fun `should retain validated ipv4 and ipv6 answers`() =
        runTest {
            // given
            val ipv4 = address("93.184.216.34")
            val ipv6 = address("2606:4700:4700::1111")
            val validator = validator(addresses = listOf(ipv4, ipv6))

            // when
            val result = validator.validate(URI("wss://peer.example"))

            // then
            val accepted = assertInstanceOf(PeerUriValidationResult.Accepted::class.java, result)
            assertEquals(listOf(ipv4, ipv6), accepted.endpoint.addresses)
        }

    @Test
    fun `should retain private addresses for allowlisted peer uri`() =
        runTest {
            // given
            val publicUri = URI("ws://peer.example:7070")
            val privateAddress = address("10.0.0.1")
            val properties =
                NetworkProperties().apply {
                    defaultNodes.add(publicUri.toString())
                }
            val validator = validator(properties, listOf(privateAddress))

            // when
            val result = validator.validate(publicUri)

            // then
            val accepted = assertInstanceOf(PeerUriValidationResult.Accepted::class.java, result)
            assertEquals(listOf(privateAddress), accepted.endpoint.addresses)
        }

    @Test
    fun `should retain private addresses when address policy is disabled`() =
        runTest {
            // given
            val privateAddress = address("127.0.0.1")
            val properties = NetworkProperties().apply { loopbackBlocked = false }
            val validator = validator(properties, listOf(privateAddress))

            // when
            val result = validator.validate(URI("ws://peer.example:7070"))

            // then
            val accepted = assertInstanceOf(PeerUriValidationResult.Accepted::class.java, result)
            assertEquals(listOf(privateAddress), accepted.endpoint.addresses)
        }

    @Test
    fun `should deduplicate resolved addresses without changing their order`() =
        runTest {
            // given
            val first = address("93.184.216.34")
            val second = address("1.1.1.1")
            val validator = validator(addresses = listOf(first, second, first))

            // when
            val result = validator.validate(URI("ws://peer.example:7070"))

            // then
            val accepted = assertInstanceOf(PeerUriValidationResult.Accepted::class.java, result)
            assertEquals(listOf(first, second), accepted.endpoint.addresses)
        }

    @Test
    fun `should reject peer uri without resolved addresses`() =
        runTest {
            // given
            val validator = validator(addresses = emptyList())

            // when
            val result = validator.validate(URI("ws://peer.example:7070"))

            // then
            assertInstanceOf(PeerUriValidationResult.Rejected::class.java, result)
        }

    private fun validator(
        properties: NetworkProperties = NetworkProperties(),
        addresses: List<InetAddress>,
    ): PeerUriValidator {
        val resolver = mockk<NetworkDnsResolver>()
        coEvery { resolver.getAllByName(any()) } returns addresses
        return PeerUriValidator(properties, resolver)
    }

    private fun address(value: String): InetAddress = InetAddress.getByName(value)
}
