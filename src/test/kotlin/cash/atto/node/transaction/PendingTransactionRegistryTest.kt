package cash.atto.node.transaction

import cash.atto.commons.AttoHash
import cash.atto.node.transaction.PendingTransactionRegistry.Result
import cash.atto.node.transaction.PendingTransactionRegistry.Stream
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BiFunction

internal class PendingTransactionRegistryTest {
    @Test
    fun `retiring last waiter preserves a waiter joining before map removal`() =
        runTest {
            // Given
            val hash = AttoHash(ByteArray(32))
            val streams = JoinBeforeRemovalMap()
            val pending = PendingTransactionRegistry(streams)
            val retiring = pending.register(hash)
            lateinit var joining: PendingTransactionRegistry.Registration
            streams.beforeRemoval = {
                // The former outside-compute decrement had already reached zero at this boundary.
                joining = pending.register(hash)
            }

            // When
            pending.remove(hash, retiring.stream)

            // Then
            assertFalse(joining.created)
            assertSame(joining.stream, pending[hash])
            val received = async(start = CoroutineStart.UNDISPATCHED) { joining.stream.flow.first() }
            val result = Result.Failed(IllegalStateException("Publication rejected"))
            pending.emit(hash, result)
            assertSame(result, received.await())
        }

    private class JoinBeforeRemovalMap : ConcurrentHashMap<AttoHash, Stream>() {
        var beforeRemoval: (() -> Unit)? = null

        override fun computeIfPresent(
            key: AttoHash,
            remappingFunction: BiFunction<in AttoHash, in Stream, out Stream?>,
        ): Stream? {
            beforeRemoval?.also { beforeRemoval = null }?.invoke()
            return super.computeIfPresent(key, remappingFunction)
        }
    }
}
