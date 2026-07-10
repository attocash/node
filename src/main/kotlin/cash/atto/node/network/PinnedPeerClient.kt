package cash.atto.node.network

import io.netty.channel.ChannelOption
import io.netty.resolver.AbstractAddressResolver
import io.netty.resolver.AddressResolver
import io.netty.resolver.AddressResolverGroup
import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.Promise
import kotlinx.coroutines.CompletableDeferred
import reactor.netty.http.client.HttpClient
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.UnknownHostException
import java.time.Duration

internal class PinnedPeerClient(
    endpoint: ValidatedPeerEndpoint,
    baseClient: HttpClient = HttpClient.newConnection(),
) : AutoCloseable {
    private val resolverGroup = PinnedPeerAddressResolverGroup(endpoint)
    private val connectedAddress = CompletableDeferred<InetSocketAddress>()

    val httpClient: HttpClient =
        baseClient
            .resolver(resolverGroup)
            .followRedirect(false)
            .noProxy()
            .option(
                ChannelOption.CONNECT_TIMEOUT_MILLIS,
                Math.toIntExact(Duration.ofSeconds(NetworkProcessor.CONNECTION_TIMEOUT_IN_SECONDS).toMillis()),
            ).responseTimeout(Duration.ofSeconds(NetworkProcessor.CONNECTION_TIMEOUT_IN_SECONDS))
            .doOnConnected { connection ->
                val remoteAddress = connection.channel().remoteAddress()
                if (remoteAddress is InetSocketAddress) {
                    connectedAddress.complete(remoteAddress)
                } else {
                    connectedAddress.completeExceptionally(
                        IllegalStateException("Peer connection has no internet socket address"),
                    )
                }
            }

    suspend fun connectedAddress(): InetSocketAddress = connectedAddress.await()

    override fun close() {
        resolverGroup.close()
    }
}

internal class PinnedPeerAddressResolverGroup(
    endpoint: ValidatedPeerEndpoint,
) : AddressResolverGroup<InetSocketAddress>() {
    private val host = requireNotNull(endpoint.publicUri.host)
    private val port = endpoint.publicUri.effectivePort()
    private val addresses = endpoint.addresses

    override fun newResolver(executor: EventExecutor): AddressResolver<InetSocketAddress> =
        object : AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress::class.java) {
            override fun doIsResolved(address: InetSocketAddress): Boolean = false

            override fun doResolve(
                unresolvedAddress: InetSocketAddress,
                promise: Promise<InetSocketAddress>,
            ) {
                val resolvedAddresses = resolveValidatedAddresses(unresolvedAddress)
                if (resolvedAddresses == null) {
                    promise.setFailure(unresolvedAddress.rejectedResolution())
                    return
                }
                promise.setSuccess(resolvedAddresses.first())
            }

            override fun doResolveAll(
                unresolvedAddress: InetSocketAddress,
                promise: Promise<List<InetSocketAddress>>,
            ) {
                val resolvedAddresses = resolveValidatedAddresses(unresolvedAddress)
                if (resolvedAddresses == null) {
                    promise.setFailure(unresolvedAddress.rejectedResolution())
                    return
                }
                promise.setSuccess(resolvedAddresses)
            }

            private fun resolveValidatedAddresses(unresolvedAddress: InetSocketAddress): List<InetSocketAddress>? {
                if (!unresolvedAddress.hostString.equals(host, ignoreCase = true) || unresolvedAddress.port != port) {
                    return null
                }

                return addresses.map { address ->
                    InetSocketAddress(InetAddress.getByAddress(host, address.address), port)
                }
            }

            private fun InetSocketAddress.rejectedResolution(): UnknownHostException =
                UnknownHostException("Address $this does not match validated peer $host:$port")
        }
}

private fun java.net.URI.effectivePort(): Int =
    when {
        port >= 0 -> port
        scheme.equals("wss", ignoreCase = true) -> 443
        else -> 80
    }
