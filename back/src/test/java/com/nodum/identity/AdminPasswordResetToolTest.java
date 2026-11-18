package com.nodum.identity;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminPasswordResetToolTest {
    @Test
    void updatesOnlyTheExistingAdministratorPasswordInATransaction() throws Exception {
        try (Connection connection = connection()) {
            char[] password = "new-local-password".toCharArray();

            AdminPasswordResetTool.resetPassword(
                    connection, "admin", password, new BCryptPasswordEncoder());

            try (var statement = connection.createStatement();
                 var result = statement.executeQuery(
                         "SELECT password_hash, updated_at, email FROM users WHERE username = 'admin'")) {
                assertTrue(result.next());
                assertTrue(new BCryptPasswordEncoder().matches("new-local-password", result.getString("password_hash")));
                assertTrue(result.getTimestamp("updated_at") != null);
                assertEquals("admin@example.test", result.getString("email"));
            }
        }
    }

    @Test
    void rollsBackWhenTheAdministratorDoesNotExist() throws Exception {
        try (Connection connection = connection()) {
            assertThrows(IllegalStateException.class, () -> AdminPasswordResetTool.resetPassword(
                    connection, "missing", "new-local-password".toCharArray(), new BCryptPasswordEncoder()));
        }
    }

    private static Connection connection() throws Exception {
        Connection connection = DriverManager.getConnection("jdbc:h2:mem:reset;DB_CLOSE_DELAY=-1");
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS users ("
                    + "username VARCHAR(255) PRIMARY KEY, "
                    + "email VARCHAR(255) NOT NULL, "
                    + "password_hash VARCHAR(255) NOT NULL, "
                    + "updated_at TIMESTAMP)");
            statement.execute("DELETE FROM users");
            statement.execute("INSERT INTO users VALUES ('admin', 'admin@example.test', 'old-hash', NULL)");
        }
        return connection;
    }
}