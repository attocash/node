package atto.node.vote.weight

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.math.BigInteger

@Configuration
@ConfigurationProperties(prefix = "atto.vote.weight")
class VoteWeightProperties {
    var minimalConfirmationWeight: BigInteger? = null
    var confirmationThreshold: Byte? = null
    var minimalRebroadcastWeight: BigInteger? = null
    var samplePeriodInDays: Long? = null
}
