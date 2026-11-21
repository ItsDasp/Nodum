package com.nodum.agent;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class ShellTerminalConfiguration implements WebSocketConfigurer {
    private final ShellTerminalHandler handler;
    public ShellTerminalConfiguration(ShellTerminalHandler handler) { this.handler = handler; }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/v1/operations/runtime/{providerId}/{instanceId}/shell/stream")
                .setAllowedOriginPatterns("*");
    }
}
