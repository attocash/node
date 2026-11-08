package cash.atto.node

import cash.atto.protocol.AttoNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component

@ExperimentalCoroutinesApi
// Info's untyped details map hides this DTO from automatic response-type hints.
@RegisterReflectionForBinding(AttoNode::class)
@Component
class NodeInfoContributor(
    val thisNode: AttoNode,
) : InfoContributor {
    override fun contribute(builder: Info.Builder) {
        builder.withDetail("this-node", thisNode)
    }
}
