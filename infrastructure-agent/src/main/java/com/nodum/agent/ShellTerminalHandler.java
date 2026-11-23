package com.nodum.agent;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ShellTerminalHandler extends AbstractWebSocketHandler {
    private final DockerModuleAgentService docker;
    private final ConcurrentHashMap<String, Terminal> terminals = new ConcurrentHashMap<>();

    public ShellTerminalHandler(DockerModuleAgentService docker) { this.docker = docker; }

    @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String path = session.getUri() == null ? "" : session.getUri().getPath();
        String[] parts = path.split("/");
        if (parts.length < 8 || !"linux-shell".equals(parts[4])) { session.close(CloseStatus.NOT_ACCEPTABLE); return; }
        String moduleId = parts[5];
        String query = session.getUri().getQuery();
        String projectId = query == null ? "" : java.util.Arrays.stream(query.split("&"))
                .filter(value -> value.startsWith("projectId=")).map(value -> value.substring("projectId=".length())).findFirst().orElse("");
        String containerId = docker.requireRunningShell(moduleId, projectId);
        Process process = new ProcessBuilder("script", "-qefc",
                "stty cols 120 rows 36; docker exec -it --user 10001:10001 --workdir /workspace "
                        + containerId + " /bin/bash -i", "/dev/null")
                .redirectErrorStream(true).start();
        Terminal terminal = new Terminal(process);
        terminals.put(session.getId(), terminal);
        Thread.ofVirtual().name("shell-pty-output").start(() -> pumpOutput(session, terminal));
    }

    @Override protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Terminal terminal = terminals.get(session.getId());
        if (terminal != null && terminal.process().isAlive()) {
            synchronized (terminal.inputLock()) {
                terminal.process().getOutputStream().write(message.getPayload().getBytes(StandardCharsets.UTF_8));
                terminal.process().getOutputStream().flush();
            }
        }
    }

    @Override protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        Terminal terminal = terminals.get(session.getId());
        if (terminal != null && terminal.process().isAlive()) {
            ByteBuffer buffer = message.getPayload().slice(); byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes);
            synchronized (terminal.inputLock()) {
                terminal.process().getOutputStream().write(bytes); terminal.process().getOutputStream().flush();
            }
        }
    }

    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Terminal terminal = terminals.remove(session.getId()); if (terminal != null) terminal.process().destroy();
    }

    private void pumpOutput(WebSocketSession session, Terminal terminal) {
        try (InputStream output = terminal.process().getInputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = output.read(buffer)) >= 0) {
                byte[] copy = java.util.Arrays.copyOf(buffer, count);
                synchronized (session) { if (session.isOpen()) session.sendMessage(new BinaryMessage(copy)); else break; }
            }
        } catch (Exception ignored) { }
        finally {
            terminals.remove(session.getId());
            if (session.isOpen()) try { session.close(CloseStatus.NORMAL); } catch (Exception ignored) { }
        }
    }

    private record Terminal(Process process, Object inputLock) {
        private Terminal(Process process) { this(process, new Object()); }
    }
}
