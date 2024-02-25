package atto.node.network.guardian

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.guard")
class GuardianProperties {
    var minimalMedian: ULong = 100U
    var toleranceMultiplier: ULong = 10U
}