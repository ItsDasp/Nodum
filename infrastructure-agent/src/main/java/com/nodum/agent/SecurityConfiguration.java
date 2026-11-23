package com.nodum.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfiguration {
    private final String serviceToken;

    public SecurityConfiguration(@Value("${NODUM_INFRASTRUCTURE_AGENT_TOKEN:}") String serviceToken) {
        this.serviceToken = serviceToken;
    }

    @Bean
    SecurityFilterChain security(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().access((authentication, context) -> {
                    String authorization = context.getRequest().getHeader("Authorization");
                    boolean authorized = !serviceToken.isBlank()
                            && ("Bearer " + serviceToken).equals(authorization);
                    return new org.springframework.security.authorization.AuthorizationDecision(authorized);
                }));
        return http.build();
    }
}
