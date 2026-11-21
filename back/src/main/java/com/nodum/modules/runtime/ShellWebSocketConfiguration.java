package com.nodum.modules.runtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
public class ShellWebSocketConfiguration implements WebSocketConfigurer {
    private final ShellWebSocketHandler handler;
    private final ShellSessionTickets tickets;

    public ShellWebSocketConfiguration(ShellWebSocketHandler handler, ShellSessionTickets tickets) {
        this.handler = handler; this.tickets = tickets;
    }

    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        HandshakeInterceptor auth = new HandshakeInterceptor() {
            @Override public boolean beforeHandshake(org.springframework.http.server.ServerHttpRequest request,
                    org.springframework.http.server.ServerHttpResponse response,
                    org.springframework.web.socket.WebSocketHandler wsHandler,
                    java.util.Map<String, Object> attributes) {
                String query = request.getURI().getRawQuery();
                String token = query == null ? null : java.util.Arrays.stream(query.split("&"))
                        .filter(part -> part.startsWith("ticket="))
                        .map(part -> java.net.URLDecoder.decode(part.substring(7), java.nio.charset.StandardCharsets.UTF_8))
                        .findFirst().orElse(null);
                ShellSessionTickets.Ticket ticket = tickets.consume(token);
                if (ticket == null) return false;
                attributes.put("projectId", ticket.projectId()); attributes.put("moduleId", ticket.moduleId());
                return true;
            }
            @Override public void afterHandshake(org.springframework.http.server.ServerHttpRequest request,
                    org.springframework.http.server.ServerHttpResponse response,
                    org.springframework.web.socket.WebSocketHandler wsHandler, Exception exception) { }
        };
        registry.addHandler(handler, "/api/v1/shell/stream").addInterceptors(auth)
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }
}
