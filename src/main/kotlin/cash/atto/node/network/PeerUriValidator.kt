package cash.atto.node.network

import org.springframework.stereotype.Component
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

@Component
class PeerUriValidator(
    private val networkProperties: NetworkProperties,
    private val dnsResolver: NetworkDnsResolver,
) {
    suspend fun validate(publicUri: URI): PeerUriValidationResult {
        val scheme = publicUri.scheme?.lowercase()
        if (scheme != "ws" && scheme != "wss") {
            return PeerUriValidationResult.Rejected("Invalid URI scheme '$scheme'")
        }

        val host = publicUri.host
        if (host.isNullOrBlank()) {
            return PeerUriValidationResult.Rejected("Missing URI host")
        }

        if (publicUri.userInfo != null || publicUri.query != null || publicUri.fragment != null || !publicUri.path.isNullOrEmpty()) {
            return PeerUriValidationResult.Rejected("Invalid URI authority or path")
        }

        if (networkProperties.isAllowlisted(publicUri)) {
            return PeerUriValidationResult.Accepted
        }

        if (!networkProperties.loopbackBlocked) {
            return PeerUriValidationResult.Accepted
        }

        val addresses =
            try {
                dnsResolver.getAllByName(host)
            } catch (e: Exception) {
                return PeerUriValidationResult.Rejected("Unable to resolve URI host")
            }

        if (addresses.isEmpty() || addresses.any { !it.isGloballyRoutable() }) {
            return PeerUriValidationResult.Rejected("URI host does not resolve to globally routable addresses")
        }

        return PeerUriValidationResult.Accepted
    }

    private fun NetworkProperties.isAllowlisted(publicUri: URI): Boolean =
        defaultNodes.any { runCatching { URI(it) }.getOrNull() == publicUri }
}

sealed interface PeerUriValidationResult {
    data object Accepted : PeerUriValidationResult

    data class Rejected(
        val reason: String,
    ) : PeerUriValidationResult
}

private fun InetAddress.isGloballyRoutable(): Boolean {
    if (isAnyLocalAddress || isLoopbackAddress || isLinkLocalAddress || isSiteLocalAddress || isMulticastAddress) {
        return false
    }

    return when (this) {
        is Inet4Address -> isGloballyRoutableIPv4()
        is Inet6Address -> isGloballyRoutableIPv6()
        else -> false
    }
}

private fun Inet4Address.isGloballyRoutableIPv4(): Boolean {
    val octets = address.map { it.toUByte().toInt() }
    val first = octets[0]
    val second = octets[1]
    val third = octets[2]

    return when {
        first == 0 -> false
        first == 10 -> false
        first == 100 && second in 64..127 -> false
        first == 127 -> false
        first == 169 && second == 254 -> false
        first == 172 && second in 16..31 -> false
        first == 192 && second == 0 -> false
        first == 192 && second == 88 && third == 99 -> false
        first == 192 && second == 168 -> false
        first == 198 && second in 18..19 -> false
        first == 198 && second == 51 && third == 100 -> false
        first == 203 && second == 0 && third == 113 -> false
        first >= 224 -> false
        else -> true
    }
}

private fun Inet6Address.isGloballyRoutableIPv6(): Boolean {
    val bytes = address.map { it.toUByte().toInt() }
    val first = bytes[0]
    val second = bytes[1]

    return when {
        first == 0x00 && bytes.all { it == 0 } -> false
        first == 0x00 && bytes.dropLast(1).all { it == 0 } && bytes.last() == 1 -> false
        (first and 0xFE) == 0xFC -> false
        first == 0xFE && (second and 0xC0) == 0x80 -> false
        first == 0xFF -> false
        first == 0x20 && second == 0x01 && bytes[2] == 0x0D && bytes[3] == 0xB8 -> false
        else -> true
    }
}
