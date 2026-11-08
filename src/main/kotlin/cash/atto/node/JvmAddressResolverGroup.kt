package cash.atto.node

import io.netty.resolver.AbstractAddressResolver
import io.netty.resolver.AddressResolver
import io.netty.resolver.AddressResolverGroup
import io.netty.util.concurrent.EventExecutor
import io.netty.util.concurrent.Promise
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.net.InetAddress
import java.net.InetSocketAddress

/** Preserve JVM/system DNS semantics on the transport owner's workers, outside Netty I/O event loops. */
internal class JvmAddressResolverGroup(
    private val dispatcher: CoroutineDispatcher,
) : AddressResolverGroup<InetSocketAddress>() {
    override fun newResolver(executor: EventExecutor): AddressResolver<InetSocketAddress> =
        object : AbstractAddressResolver<InetSocketAddress>(executor, InetSocketAddress::class.java) {
            private val scope = CoroutineScope(SupervisorJob() + dispatcher)

            override fun doIsResolved(address: InetSocketAddress): Boolean = !address.isUnresolved

            override fun doResolve(
                address: InetSocketAddress,
                promise: Promise<InetSocketAddress>,
            ) {
                resolve(promise) { InetSocketAddress(InetAddress.getByName(address.hostString), address.port) }
            }

            override fun doResolveAll(
                address: InetSocketAddress,
                promise: Promise<MutableList<InetSocketAddress>>,
            ) {
                resolve(promise) {
                    InetAddress.getAllByName(address.hostString).map { InetSocketAddress(it, address.port) }.toMutableList()
                }
            }

            private fun <T> resolve(
                promise: Promise<T>,
                lookup: () -> T,
            ) {
                val job =
                    scope.launch {
                        try {
                            promise.trySuccess(lookup())
                        } catch (exception: Exception) {
                            promise.tryFailure(exception)
                        }
                    }
                promise.addListener { if (it.isCancelled) job.cancel() }
            }

            override fun close() {
                scope.cancel()
            }
        }
}
