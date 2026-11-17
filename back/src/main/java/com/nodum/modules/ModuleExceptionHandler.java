package com.nodum.modules;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = ModuleController.class)
public class ModuleExceptionHandler {
    @ExceptionHandler(com.nodum.modules.providers.GatewayModule.GatewayRateLimitException.class)
    public ResponseEntity<?> rateLimited(RuntimeException error) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(ModuleLifecycleService.InvalidModuleConfigurationException.class)
    public ResponseEntity<?> invalidConfiguration(ModuleLifecycleService.InvalidModuleConfigurationException error) {
        return ResponseEntity.unprocessableEntity().body(Map.of("message", error.getMessage(), "errors", error.getErrors()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalidModuleRequest(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> moduleConflict(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", error.getMessage()));
    }
}
