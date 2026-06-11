package cash.atto.node.network

import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.net.URI

class HandshakeCallbackServiceTest {
    @Test
    fun `should not call callback client for unsafe callback uri`() =
        runTest {
            val callbackClient = mockk<HandshakeCallbackClient>()
            val service = callbackService(callbackClient = callbackClient)
            var requestBuilt = false

            val unsafeUris =
                listOf(
                    URI("ws://127.0.0.1:7070"),
                    URI("ws://10.0.0.1:7070"),
                    URI("ws://169.254.169.254:80"),
                    URI("ws://224.0.0.1:7070"),
                    URI("ws://192.0.2.10:7070"),
                )

            unsafeUris.forEach { publicUri ->
                val result =
                    service.post("198.51.100.10", publicUri) {
                        requestBuilt = true
                        mockk()
                    }

                assertInstanceOf(HandshakeCallbackResult.Rejected::class.java, result)
                assertEquals(HttpStatusCode.BadRequest, (result as HandshakeCallbackResult.Rejected).status)
            }

            assertFalse(requestBuilt)
            coVerify(exactly = 0) { callbackClient.post(any(), any()) }
        }

    @Test
    fun `should call callback client for safe callback uri`() =
        runTest {
            val callbackClient = mockk<HandshakeCallbackClient>()
            val request = mockk<CounterChallengeResponse>()
            val service = callbackService(callbackClient = callbackClient)

            coEvery {
                callbackClient.post(URI("http://93.184.216.34:7070/handshakes"), request)
            } returns HandshakeCallbackResult.Completed(HttpStatusCode.OK, null)

            val result =
                service.post("198.51.100.10", URI("ws://93.184.216.34:7070")) {
                    request
                }

            assertInstanceOf(HandshakeCallbackResult.Completed::class.java, result)
            coVerify(exactly = 1) {
                callbackClient.post(URI("http://93.184.216.34:7070/handshakes"), request)
            }
        }

    private fun callbackService(
        properties: NetworkProperties = NetworkProperties(),
        callbackClient: HandshakeCallbackClient,
    ): HandshakeCallbackService =
        HandshakeCallbackService(
            peerUriValidator = PeerUriValidator(properties, NetworkDnsResolver()),
            callbackClient = callbackClient,
        )
}
