package cash.atto.node

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@ConfigurationProperties(prefix = "atto.signer")
class SignerProperties {
    lateinit var backend: Backend
    var token: String? = null
    var remoteUrl: String? = null
    var key: String? = null

    enum class Backend {
        LOCAL,
        REMOTE,
    }
}
