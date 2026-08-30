package cash.atto.node.network

import cash.atto.protocol.AttoNode
import kotlinx.coroutines.CompletableDeferred

internal class OutboundConnectionAttempt {
    private val authenticatedNode = CompletableDeferred<AttoNode>()

    fun authenticate(node: AttoNode): Boolean = authenticatedNode.complete(node)

    fun expire(cause: Throwable): Boolean = authenticatedNode.completeExceptionally(cause)

    suspend fun awaitNode(): AttoNode = authenticatedNode.await()
}
