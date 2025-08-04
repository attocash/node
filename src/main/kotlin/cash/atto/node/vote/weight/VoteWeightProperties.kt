package cash.atto.node.vote.weight

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.vote.weight")
class VoteWeightProperties {
    var minimalConfirmationWeight: String? = null
    var confirmationThreshold: Byte? = null
    var minimalRebroadcastWeight: String? = null
    var samplePeriodInDays: Long? = null
}
