package cash.atto.node

import cash.atto.protocol.AttoNode
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

@Component
class GlobalRequestInterceptor(
    private val thisNode: AttoNode,
    private val nodeProperties: NodeProperties,
) : WebFilter {
    override fun filter(
        exchange: ServerWebExchange,
        chain: WebFilterChain,
    ): Mono<Void> {
        if (!nodeProperties.forceApi && thisNode.isVoter() && thisNode.isNotHistorical() && exchange.request.uri.port == 8080) {
            return Mono.error(
                ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "This node is a voter. Exposing port 8080 on a voter node is insecure and not recommended. " +
                        "If you understand the risks, you can override this by setting the environment variable" +
                        " `ATTO_NODE_FORCE_HISTORICAL` or `ATTO_NODE_FORCE_API`.",
                ),
            )
        }

        return chain.filter(exchange)
    }
}
