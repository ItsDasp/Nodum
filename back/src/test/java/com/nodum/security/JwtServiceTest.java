package com.nodum.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {
    @Test
    void acceptsStrongSecretAndRejectsInvalidToken() {
        JwtService service = serviceWith("a".repeat(64));

        String token = service.generateToken("admin");

        assertTrue(service.isTokenValid(token));
        assertFalse(service.isTokenValid(token + "invalid"));
    }

    @Test
    void rejectsMissingShortAndSampleSecrets() {
        JwtService shortSecret = serviceWith("short-secret");
        JwtService sampleSecret = serviceWith("change-this-" + "a".repeat(64));

        assertThrows(IllegalStateException.class, shortSecret::validateConfiguration);
        assertThrows(IllegalStateException.class, sampleSecret::validateConfiguration);
    }

    private static JwtService serviceWith(String secret) {
        JwtService service = new JwtService();
        try {
            var field = JwtService.class.getDeclaredField("secret");
            field.setAccessible(true);
            field.set(service, secret);
            var expiration = JwtService.class.getDeclaredField("expiration");
            expiration.setAccessible(true);
            expiration.setLong(service, 86_400_000L);
            return service;
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}