package com.nodum.identity;

import com.nodum.security.JwtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class AdminAuthenticationTest {
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    void createsAdministratorWithBcryptHashWhenMissing() {
        UserRepository userRepository = mock(UserRepository.class);
        AdminInitializer initializer = new AdminInitializer(userRepository, passwordEncoder);
        setAdminConfiguration(initializer, "admin", "initial-password", "admin@example.test");
        when(userRepository.existsByUsername("admin")).thenReturn(false);

        initializer.run();

        ArgumentCaptor<User> savedUser = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(savedUser.capture());
        assertEquals("admin", savedUser.getValue().getUsername());
        assertTrue(passwordEncoder.matches("initial-password", savedUser.getValue().getPasswordHash()));
    }

    @Test
    void doesNotOverwriteExistingAdministratorPassword() {
        UserRepository userRepository = mock(UserRepository.class);
        AdminInitializer initializer = new AdminInitializer(userRepository, passwordEncoder);
        setAdminConfiguration(initializer, "admin", "new-password", "admin@example.test");
        when(userRepository.existsByUsername("admin")).thenReturn(true);

        initializer.run();

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void changesPasswordOnlyWithCurrentPassword() {
        UserRepository userRepository = mock(UserRepository.class);
        JwtService jwtService = jwtService();
        AuthenticationService service = new AuthenticationService(userRepository, passwordEncoder, jwtService);
        User user = user("admin", "old-password");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        service.changePassword("admin", "old-password", "new-password-123");

        assertTrue(passwordEncoder.matches("new-password-123", user.getPasswordHash()));
        assertNotEquals("old-password", user.getPasswordHash());
        verify(userRepository).save(user);
    }

    @Test
    void rejectsPasswordChangeWithWrongCurrentPassword() {
        UserRepository userRepository = mock(UserRepository.class);
        JwtService jwtService = jwtService();
        AuthenticationService service = new AuthenticationService(userRepository, passwordEncoder, jwtService);
        User user = user("admin", "old-password");
        String originalHash = user.getPasswordHash();
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertThrows(IllegalArgumentException.class,
                () -> service.changePassword("admin", "wrong-password", "new-password-123"));

        assertEquals(originalHash, user.getPasswordHash());
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void loginUsesPersistedBcryptHashAndReturnsJwt() {
        UserRepository userRepository = mock(UserRepository.class);
        JwtService jwtService = jwtService();
        AuthenticationService service = new AuthenticationService(userRepository, passwordEncoder, jwtService);
        User user = user("admin", "correct-password");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(user));

        assertTrue(jwtService.isTokenValid(service.login("admin", "correct-password")));
        assertThrows(IllegalArgumentException.class, () -> service.login("admin", "wrong-password"));
    }

    private static JwtService jwtService() {
        JwtService service = new JwtService();
        setField(service, "secret", "a".repeat(64));
        setField(service, "expiration", 86_400_000L);
        return service;
    }

    private static User user(String username, String password) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@example.test");
        user.setPasswordHash(encoder.encode(password));
        return user;
    }

    private static void setAdminConfiguration(
            AdminInitializer initializer, String username, String password, String email) {
        setField(initializer, "adminUsername", username);
        setField(initializer, "adminPassword", password);
        setField(initializer, "adminEmail", email);
    }

    private static void setField(Object target, String name, Object value) {
        try {
            var field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}