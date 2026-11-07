package cash.atto.node.election

import kotlinx.coroutines.runBlocking
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.stereotype.Component

@Component
class ElectionInfoContributor(
    val election: Election,
) : InfoContributor {
    override fun contribute(builder: Info.Builder) {
        val elections = runBlocking { election.getElections() }
        val details =
            mapOf(
                "weights" to elections,
                "active" to elections.size,
            )
        builder.withDetail("elections", details)
    }
}
