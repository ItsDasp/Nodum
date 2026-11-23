package com.nodum.identity;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.Console;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;

/**
 * Explicit local-only password recovery tool. It is not a Spring bean or an HTTP endpoint.
 */
public final class AdminPasswordResetTool {
    private static final int MINIMUM_PASSWORD_LENGTH = 12;

    private AdminPasswordResetTool() {
    }

    public static void main(String[] args) {
        Console console = System.console();
        if (console == null) {
            System.err.println("Password reset requires an interactive terminal.");
            System.exit(1);
        }

        String databaseUrl = requiredEnvironment("SPRING_DATASOURCE_URL");
        String databaseUsername = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
        String databasePassword = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
        String adminUsername = requiredEnvironment("NODUM_ADMIN_USERNAME");

        char[] newPassword = console.readPassword("New administrator password: ");
        char[] confirmation = console.readPassword("Confirm administrator password: ");
        try {
            if (!Arrays.equals(newPassword, confirmation)) {
                throw new IllegalArgumentException("Passwords do not match.");
            }

            try (Connection connection = DriverManager.getConnection(
                    databaseUrl, databaseUsername, databasePassword)) {
                resetPassword(connection, adminUsername, newPassword, new BCryptPasswordEncoder());
            }
            console.printf("Exactly one administrator password was updated.%n");
        } catch (IllegalArgumentException | SQLException exception) {
            System.err.println("Administrator password reset failed: " + exception.getMessage());
            System.exit(1);
        } finally {
            Arrays.fill(newPassword, '\0');
            Arrays.fill(confirmation, '\0');
        }
    }

    static void resetPassword(
            Connection connection,
            String username,
            char[] newPassword,
            PasswordEncoder passwordEncoder) throws SQLException {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Administrator username must not be blank.");
        }
        if (newPassword == null || newPassword.length < MINIMUM_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "Administrator password must contain at least " + MINIMUM_PASSWORD_LENGTH + " characters.");
        }

        boolean originalAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            int updatedRows;
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE users SET password_hash = ?, updated_at = CURRENT_TIMESTAMP WHERE username = ?")) {
                statement.setString(1, passwordEncoder.encode(new String(newPassword)));
                statement.setString(2, username);
                updatedRows = statement.executeUpdate();
            }

            if (updatedRows != 1) {
                connection.rollback();
                throw new IllegalStateException("Expected exactly one administrator row, updated " + updatedRows + ".");
            }
            connection.commit();
        } catch (RuntimeException | SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(originalAutoCommit);
        }
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set before running the password reset tool.");
        }
        return value;
    }
}