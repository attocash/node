package cash.atto.node.stream

import cash.atto.commons.node.AttoStreamProtocol
import cash.atto.commons.spring.AttoStreamWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import reactor.netty.http.server.WebsocketServerSpec

@Configuration(proxyBeanMethods = false)
class NodeStreamWebSocketConfiguration {
    @Bean
    fun publicStreamHandlerMapping(dispatcher: NodeStreamDispatcher): SimpleUrlHandlerMapping =
        SimpleUrlHandlerMapping(
            mapOf(
                AttoStreamProtocol.PATH to AttoStreamWebSocketHandler({ request, _, mutable -> dispatcher.plan(request, mutable) }),
            ),
            -1,
        )

    @Bean
    fun publicStreamWebSocketHandlerAdapter(): WebSocketHandlerAdapter {
        val strategy =
            ReactorNettyRequestUpgradeStrategy {
                WebsocketServerSpec.builder().maxFramePayloadLength(AttoStreamWebSocketHandler.MAX_FRAME_PAYLOAD_LENGTH)
            }
        return WebSocketHandlerAdapter(HandshakeWebSocketService(strategy)).apply { order = 1 }
    }
}
