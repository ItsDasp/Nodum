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
        if (adminPassword == null || adminPassword.isEmpty()) {
            throw new IllegalStateException("NODUM_ADMIN_PASSWORD environment variable must be set");
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
