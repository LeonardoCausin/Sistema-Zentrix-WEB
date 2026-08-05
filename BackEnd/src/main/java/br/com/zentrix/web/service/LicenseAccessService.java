package br.com.zentrix.web.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LicenseAccessService {
    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;

    public LicenseAccessService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
    }

    public void requireActive(String tenantId, String path) {
        if (tenantId == null || tenantId.isBlank() || "legacy".equalsIgnoreCase(tenantId) || isAdminPath(path)) {
            return;
        }
        initializer.ensureReady();
        Map<String, Object> tenant = jdbcTemplate.queryForList("""
                SELECT status, block_reason AS blockReason
                FROM tenants
                WHERE id = ?
                LIMIT 1
                """, tenantId).stream().findFirst().orElse(Map.of("status", "ACTIVE"));
        String tenantStatus = String.valueOf(tenant.getOrDefault("status", "ACTIVE"));
        if (blocked(tenantStatus)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, blockedMessage(tenant.get("blockReason")));
        }

        List<Map<String, Object>> licenses = jdbcTemplate.queryForList("""
                SELECT status, expires_at AS expiresAt
                FROM licenses
                WHERE tenant_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, tenantId);
        if (licenses.isEmpty()) {
            return;
        }
        Map<String, Object> license = licenses.get(0);
        String status = String.valueOf(license.get("status"));
        if (blocked(status)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "A assinatura desta loja esta bloqueada. Entre em contato com o suporte Zentrix para regularizar o acesso.");
        }
        Object expiresAt = license.get("expiresAt");
        if (expiresAt instanceof Timestamp timestamp && timestamp.toLocalDateTime().isBefore(LocalDateTime.now())) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "A assinatura desta loja esta vencida. Entre em contato com o suporte Zentrix para regularizar o acesso.");
        }
    }

    private String blockedMessage(Object reason) {
        String text = reason == null ? "" : String.valueOf(reason).trim();
        if (text.isBlank()) {
            return "A loja esta bloqueada. Entre em contato com o suporte Zentrix para regularizar o acesso.";
        }
        return "A loja esta bloqueada: " + text + ". Entre em contato com o suporte Zentrix para regularizar o acesso.";
    }

    private boolean isAdminPath(String path) {
        String value = path == null ? "" : path;
        return value.startsWith("/api/zentrix-admin")
                || value.startsWith("/api/local-admin")
                || value.startsWith("/api/auth");
    }

    private boolean blocked(String status) {
        String value = status == null ? "" : status.trim().toUpperCase();
        return value.equals("BLOCKED")
                || value.equals("SUSPENDED")
                || value.equals("EXPIRED")
                || value.equals("CANCELLED")
                || value.equals("CANCELED")
                || value.equals("INACTIVE");
    }
}
