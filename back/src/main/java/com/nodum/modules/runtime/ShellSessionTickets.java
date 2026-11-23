package com.nodum.modules.runtime;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

@Component
public class ShellSessionTickets {
    private static final long TTL_SECONDS = 30;
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Ticket> tickets = new ConcurrentHashMap<>();

    public String issue(UUID projectId, UUID moduleId) {
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(value, new Ticket(projectId, moduleId, Instant.now().plusSeconds(TTL_SECONDS)));
        tickets.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(Instant.now()));
        return value;
    }

    public Ticket consume(String value) {
        Ticket ticket = value == null ? null : tickets.remove(value);
        return ticket != null && ticket.expiresAt().isAfter(Instant.now()) ? ticket : null;
    }

    public record Ticket(UUID projectId, UUID moduleId, Instant expiresAt) {}
}
