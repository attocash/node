package atto.node.election

import atto.node.NotVoterCondition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.springframework.boot.actuate.info.Info
import org.springframework.boot.actuate.info.InfoContributor
import org.springframework.context.annotation.Conditional
import org.springframework.stereotype.Component

@ExperimentalCoroutinesApi
@Component
class ElectionInfoContributor(val election: Election) : InfoContributor {

    // TODO: Improve contributor
    override fun contribute(builder: Info.Builder) {
        val election = mapOf(
            "weights" to runBlocking { election.getElections() },
            "active" to runBlocking { election.getSize() }
        )
        builder.withDetail("elections", election)
    }

}