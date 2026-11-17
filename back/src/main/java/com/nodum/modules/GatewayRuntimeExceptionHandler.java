package com.nodum.modules;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice(assignableTypes = GatewayRuntimeController.class)
public class GatewayRuntimeExceptionHandler {
    @ExceptionHandler(com.nodum.modules.providers.GatewayModule.GatewayRateLimitException.class)
    public ResponseEntity<?> rateLimited(RuntimeException error) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(GatewayRuntimeController.GatewayRuntimeException.class)
    public ResponseEntity<?> gatewayError(GatewayRuntimeController.GatewayRuntimeException error) {
        return ResponseEntity.status(error.getStatus()).body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<?> invalidRoute(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of("message", error.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> upstreamFailure(IllegalStateException error) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", error.getMessage()));
    }
}
