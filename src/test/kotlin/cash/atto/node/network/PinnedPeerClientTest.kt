package cash.atto.node.network

import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.ssl.util.SelfSignedCertificate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.netty.http.Http11SslContextSpec
import reactor.netty.http.client.HttpClient
import reactor.netty.http.server.HttpServer
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import javax.net.ssl.ExtendedSSLSession
import javax.net.ssl.SNIHostName

class PinnedPeerClientTest {
    @Test
    fun `should preserve hostname for host sni and certificate verification`(): Unit =
        runBlocking {
            // given
            val certificate = SelfSignedCertificate("peer.invalid")
            val receivedHost = CompletableFuture<String>()
            val receivedServerName = CompletableFuture<String>()
            val server =
                HttpServer
                    .create()
                    .host("127.0.0.1")
                    .port(0)
                    .secure { ssl ->
                        ssl.sslContext(
                            Http11SslContextSpec.forServer(certificate.certificate(), certificate.privateKey()),
                        )
                    }.handle { request, response ->
                        request.withConnection { connection ->
                            val sslSession =
                                connection
                                    .channel()
                                    .pipeline()
                                    .sslHandler()
                                    .engine()
                                    .session as ExtendedSSLSession
                            val serverName = sslSession.requestedServerNames.single() as SNIHostName
                            receivedServerName.complete(serverName.asciiName)
                        }
                        receivedHost.complete(request.requestHeaders()[HttpHeaderNames.HOST])
                        response.status(200).send()
                    }.bindNow()
            val publicUri = URI("wss://peer.invalid:${server.port()}")
            val endpoint = ValidatedPeerEndpoint(publicUri, listOf(InetAddress.getByName("127.0.0.1")))
            val trustedClient =
                HttpClient.newConnection().secure { ssl ->
                    ssl.sslContext(
                        Http11SslContextSpec.forClient().configure { builder ->
                            builder.trustManager(certificate.certificate())
                        },
                    )
                }
            val client = PinnedPeerClient(endpoint, trustedClient)

            try {
                // when
                val status =
                    client.httpClient
                        .get()
                        .uri("https://peer.invalid:${server.port()}/")
                        .responseSingle { response, body -> body.thenReturn(response.status().code()) }
                        .block(Duration.ofSeconds(5))

                // then
                assertEquals(200, status)
                assertEquals("peer.invalid:${server.port()}", receivedHost.get())
                assertEquals("peer.invalid", receivedServerName.get())
                assertEquals(InetAddress.getByName("127.0.0.1"), client.connectedAddress().address)
            } finally {
                client.close()
                server.disposeNow()
                certificate.delete()
            }
        }
}

private fun io.netty.channel.ChannelPipeline.sslHandler(): io.netty.handler.ssl.SslHandler =
    requireNotNull(get(io.netty.handler.ssl.SslHandler::class.java))
