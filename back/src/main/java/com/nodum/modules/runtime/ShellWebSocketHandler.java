package com.nodum.modules.runtime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ShellWebSocketHandler extends AbstractWebSocketHandler {
    private final URI agentUrl;
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ConcurrentHashMap<String, WebSocket> sessions = new ConcurrentHashMap<>();

    public ShellWebSocketHandler(@Value("${nodum.infrastructure.agent-url:}") String agentUrl,
                                 @Value("${nodum.infrastructure.agent-token:}") String token) {
        this.agentUrl = agentUrl == null || agentUrl.isBlank() ? null : URI.create(agentUrl.replaceAll("/$", ""));
        this.token = token;
    }

    @Override public void afterConnectionEstablished(WebSocketSession browser) {
        try {
            UUID projectId = (UUID) browser.getAttributes().get("projectId");
            UUID moduleId = (UUID) browser.getAttributes().get("moduleId");
            if (projectId == null || moduleId == null || agentUrl == null || token == null || token.isBlank()) {
                browser.close(CloseStatus.NOT_ACCEPTABLE); return;
            }
            String scheme = "https".equalsIgnoreCase(agentUrl.getScheme()) ? "wss" : "ws";
            URI uri = new URI(scheme, null, agentUrl.getHost(), agentUrl.getPort(),
                    "/v1/operations/runtime/linux-shell/" + moduleId + "/shell/stream",
                    "projectId=" + projectId, null);
            client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5))
                    .header("Authorization", "Bearer " + token)
                    .buildAsync(uri, new WebSocket.Listener() {
                        @Override public void onOpen(WebSocket socket) {
                            sessions.put(browser.getId(), socket); socket.request(1);
                        }
                        @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
                            byte[] bytes = new byte[data.remaining()]; data.get(bytes);
                            sendBinary(browser, bytes); socket.request(1); return null;
                        }
                        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
                            sendText(browser, data.toString()); socket.request(1); return null;
                        }
                        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
                            sessions.remove(browser.getId()); closeBrowser(browser, code, reason); return null;
                        }
                        @Override public void onError(WebSocket socket, Throwable error) {
                            sessions.remove(browser.getId()); closeBrowser(browser, 1011, "Agente de shell desconectado");
                        }
                    }).exceptionally(error -> { closeBrowser(browser, 1011, "No se pudo abrir la terminal"); return null; });
        } catch (Exception failure) { closeBrowser(browser, 1011, "No se pudo abrir la terminal"); }
    }

    @Override protected void handleTextMessage(WebSocketSession browser, TextMessage message) {
        WebSocket socket = sessions.get(browser.getId());
        if (socket != null) socket.sendText(message.getPayload(), true);
    }

    @Override protected void handleBinaryMessage(WebSocketSession browser, BinaryMessage message) {
        WebSocket socket = sessions.get(browser.getId());
        if (socket != null) {
            ByteBuffer data = message.getPayload().slice(); byte[] bytes = new byte[data.remaining()]; data.get(bytes);
            socket.sendBinary(ByteBuffer.wrap(bytes), true);
        }
    }

    @Override public void afterConnectionClosed(WebSocketSession browser, CloseStatus status) {
        WebSocket socket = sessions.remove(browser.getId()); if (socket != null) socket.sendClose(1000, "Sesión cerrada");
    }

    private void sendBinary(WebSocketSession session, byte[] bytes) {
        try { synchronized (session) { if (session.isOpen()) session.sendMessage(new BinaryMessage(bytes)); } }
        catch (Exception ignored) { closeBrowser(session, 1011, "Error en la salida del terminal"); }
    }
    private void sendText(WebSocketSession session, String text) {
        try { synchronized (session) { if (session.isOpen()) session.sendMessage(new TextMessage(text)); } }
        catch (Exception ignored) { closeBrowser(session, 1011, "Error en la salida del terminal"); }
    }
    private void closeBrowser(WebSocketSession session, int code, String reason) {
        try { if (session.isOpen()) session.close(new CloseStatus(code, reason)); } catch (Exception ignored) { }
    }
}
