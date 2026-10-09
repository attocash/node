package cash.atto.node.network

import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.URI

@OptIn(ExperimentalCoroutinesApi::class)
class HandshakeCallbackServiceTest {
    private val safePublicUri = URI("ws://93.184.216.34:7070")

    @Test
    fun `should not call callback client for unsafe callback uri`() =
        runTest {
            // Given
            val callbackClient = mockk<HandshakeCallbackClient>()
            val dnsResolver = mockk<NetworkDnsResolver>()
            coEvery { dnsResolver.getAllByName(any()) } answers {
                listOf(InetAddress.getByName(firstArg()))
            }
            val service = callbackService(callbackClient = callbackClient, dnsResolver = dnsResolver)
            var requestBuilt = false

            val unsafeUris =
                listOf(
                    URI("ws://127.0.0.1:7070"),
                    URI("ws://10.0.0.1:7070"),
                    URI("ws://169.254.169.254:80"),
                    URI("ws://224.0.0.1:7070"),
                    URI("ws://192.0.2.10:7070"),
                )

            // When
            unsafeUris.forEach { publicUri ->
                val result =
                    service.post("198.51.100.10", publicUri) {
                        requestBuilt = true
                        mockk()
                    }

                // Then
                assertInstanceOf(HandshakeCallbackResult.Rejected::class.java, result)
                assertEquals(HttpStatusCode.BadRequest, (result as HandshakeCallbackResult.Rejected).status)
            }

            // Then
            assertFalse(requestBuilt)
            coVerify(exactly = unsafeUris.size) { dnsResolver.getAllByName(any()) }
            coVerify(exactly = 0) { callbackClient.post(any(), any()) }
        }

    @Test
    fun `should call callback client for safe callback uri`() =
        runTest {
            // Given
            val callbackClient = mockk<HandshakeCallbackClient>()
            val request = mockk<CounterChallengeResponse>()
            val service = callbackService(safeCallbackProperties(), callbackClient)

            coEvery {
                callbackClient.post(URI("http://93.184.216.34:7070/handshakes"), request)
            } returns HandshakeCallbackResult.Completed(HttpStatusCode.OK, null)

            // When
            val result =
                service.post("198.51.100.10", safePublicUri) {
                    request
                }

            // Then
            assertInstanceOf(HandshakeCallbackResult.Completed::class.java, result)
            coVerify(exactly = 1) {
                callbackClient.post(URI("http://93.184.216.34:7070/handshakes"), request)
            }
        }

    @Test
    fun `cancels stalled request factory after five seconds and permits a later callback`() =
        runTest {
            // Given
            val callbackClient = mockk<HandshakeCallbackClient>()
            val request = mockk<CounterChallengeResponse>()
            val result = HandshakeCallbackResult.Completed(HttpStatusCode.OK, null)
            val service = callbackService(safeCallbackProperties(), callbackClient)
            var factoryCancelled = false
            coEvery { callbackClient.post(any(), any()) } returns result

            // When
            val stalledCall =
                captureCallbackResult(service) {
                    awaitCancellationWithCleanup { factoryCancelled = true }
                }
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            // Then
            assertInstanceOf(TimeoutCancellationException::class.java, stalledCall.await().exceptionOrNull())
            assertTrue(factoryCancelled)
            coVerify(exactly = 0) { callbackClient.post(any(), any()) }

            // When
            var nextFactoryCalled = false
            val nextResult =
                service.post("198.51.100.10", safePublicUri) {
                    nextFactoryCalled = true
                    request
                }

            // Then
            assertSame(result, nextResult)
            assertTrue(nextFactoryCalled)
            coVerify(exactly = 1) { callbackClient.post(any(), request) }
        }

    @Test
    fun `callback wait receives only the request factory remaining deadline`() =
        runTest {
            // Given
            val callbackClient = mockk<HandshakeCallbackClient>()
            val request = mockk<CounterChallengeResponse>()
            val service = callbackService(safeCallbackProperties(), callbackClient)
            var callbackStarted = false
            var callbackCancelled = false
            coEvery { callbackClient.post(any(), request) } coAnswers {
                callbackStarted = true
                awaitCancellationWithCleanup { callbackCancelled = true }
            }

            // When
            val callbackCall =
                captureCallbackResult(service) {
                    delay(4_000)
                    request
                }
            runCurrent()
            advanceTimeBy(4_000)
            runCurrent()

            // Then
            assertTrue(callbackStarted)
            assertFalse(callbackCall.isCompleted)

            // When
            advanceTimeBy(999)
            runCurrent()

            // Then
            assertFalse(callbackCall.isCompleted)

            // When
            advanceTimeBy(1)
            runCurrent()

            // Then
            assertInstanceOf(TimeoutCancellationException::class.java, callbackCall.await().exceptionOrNull())
            assertTrue(callbackCancelled)
            assertEquals(5_000L, testScheduler.currentTime)
        }

    @Test
    fun `parent cancellation propagates through request factory`() =
        runTest {
            // Given
            val callbackClient = mockk<HandshakeCallbackClient>()
            val service = callbackService(safeCallbackProperties(), callbackClient)
            val requestContext = currentCoroutineContext()
            val parentJob = Job(requestContext[Job])
            val requestScope = CoroutineScope(requestContext + parentJob)
            val observedCancellation = CompletableDeferred<CancellationException>()
            val request = requestScope.observeParentCancellation(service, observedCancellation)
            runCurrent()

            // When
            val parentCancellation = CancellationException("caller cancelled")
            parentJob.cancel(parentCancellation)
            runCurrent()

            // Then
            val cancellation = observedCancellation.await()
            assertInstanceOf(CancellationException::class.java, cancellation)
            assertEquals(parentCancellation.message, cancellation.message)
            assertTrue(request.isCancelled)
            coVerify(exactly = 0) { callbackClient.post(any(), any()) }
        }

    private fun callbackService(
        properties: NetworkProperties = NetworkProperties(),
        callbackClient: HandshakeCallbackClient,
        dnsResolver: NetworkDnsResolver = NetworkDnsResolver(),
    ): HandshakeCallbackService =
        HandshakeCallbackService(
            peerUriValidator = PeerUriValidator(properties, dnsResolver),
            callbackClient = callbackClient,
        )

    private fun safeCallbackProperties(): NetworkProperties =
        NetworkProperties().apply {
            defaultNodes += safePublicUri.toString()
        }

    private fun CoroutineScope.captureCallbackResult(
        service: HandshakeCallbackService,
        requestFactory: suspend () -> CounterChallengeResponse,
    ): Deferred<Result<HandshakeCallbackResult>> =
        async {
            runCatching {
                service.post("198.51.100.10", safePublicUri, requestFactory)
            }
        }

    private suspend fun <T> awaitCancellationWithCleanup(onCancellation: () -> Unit): T =
        try {
            awaitCancellation()
        } finally {
            onCancellation()
        }

    private fun CoroutineScope.observeParentCancellation(
        service: HandshakeCallbackService,
        observedCancellation: CompletableDeferred<CancellationException>,
    ): Job =
        launch {
            try {
                service.postUntilRequestFactoryCancellation(safePublicUri)
            } catch (e: CancellationException) {
                observedCancellation.complete(e)
                throw e
            }
        }

    private suspend fun HandshakeCallbackService.postUntilRequestFactoryCancellation(uri: URI) =
        post("198.51.100.10", uri) {
            awaitCancellation()
        }
}
