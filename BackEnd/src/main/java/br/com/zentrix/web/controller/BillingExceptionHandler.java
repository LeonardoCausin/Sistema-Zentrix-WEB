package br.com.zentrix.web.controller;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(assignableTypes = {BillingController.class, AsaasWebhookController.class})
public class BillingExceptionHandler {
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", exception.getStatusCode().value());
        body.put("error", exception.getStatusCode().toString());
        body.put("message", exception.getReason() == null ? "Falha ao processar o pagamento." : exception.getReason());
        return ResponseEntity.status(exception.getStatusCode()).body(body);
    }
}
