package cash.atto.node.network

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI

class PeerUriValidatorTest {
    @Test
    fun `should accept globally routable peer uri`() =
        runTest {
            val validator = PeerUriValidator(NetworkProperties(), NetworkDnsResolver())

            val result = validator.validate(URI("ws://93.184.216.34:7070"))

            assertInstanceOf(PeerUriValidationResult.Accepted::class.java, result)
        }

    @Test
    fun `should reject private peer uri`() =
        runTest {
            val validator = PeerUriValidator(NetworkProperties(), NetworkDnsResolver())

            val result = validator.validate(URI("ws://10.0.0.1:7070"))

            assertInstanceOf(PeerUriValidationResult.Rejected::class.java, result)
        }

    @Test
    fun `should reject metadata peer uri`() =
        runTest {
            val validator = PeerUriValidator(NetworkProperties(), NetworkDnsResolver())

            val result = validator.validate(URI("ws://169.254.169.254:80"))

            assertInstanceOf(PeerUriValidationResult.Rejected::class.java, result)
        }

    @Test
    fun `should accept allowlisted private peer uri`() =
        runTest {
            val properties =
                NetworkProperties().apply {
                    defaultNodes.add("ws://10.0.0.1:7070")
                }
            val validator = PeerUriValidator(properties, NetworkDnsResolver())

            val result = validator.validate(URI("ws://10.0.0.1:7070"))

            assertInstanceOf(PeerUriValidationResult.Accepted::class.java, result)
        }
}
