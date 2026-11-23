package com.nodum.identity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class AdminInitializer implements CommandLineRunner {
    
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    
    @Value("${nodum.admin.username}")
    private String adminUsername;
    
    @Value("${nodum.admin.password}")
    private String adminPassword;
    
    @Value("${nodum.admin.email}")
    private String adminEmail;
    
    public AdminInitializer(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }
    
    @Override
    public void run(String... args) {
        if (adminUsername == null || adminUsername.isBlank()
                || adminPassword == null || adminPassword.isBlank()
                || adminEmail == null || adminEmail.isBlank()) {
            throw new IllegalStateException(
                    "NODUM_ADMIN_USERNAME, NODUM_ADMIN_PASSWORD and NODUM_ADMIN_EMAIL must be set");
        }
        if (adminPassword.contains("replace-this") || adminPassword.contains("change-this")) {
            throw new IllegalStateException("NODUM_ADMIN_PASSWORD must not contain a sample value");
        }
        
        if (!userRepository.existsByUsername(adminUsername)) {
            User admin = new User();
            admin.setUsername(adminUsername);
            admin.setEmail(adminEmail);
            admin.setPasswordHash(passwordEncoder.encode(adminPassword));
            
            userRepository.save(admin);
        }
    }
}
