package atto.node.node

import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component

@ExperimentalCoroutinesApi
@Component
class NodeInfoContributor(val thisNode: atto.protocol.AttoNode) : InfoContributor {

    override fun contribute(builder: Info.Builder) {
        builder.withDetail("this-node", thisNode)
    }

}