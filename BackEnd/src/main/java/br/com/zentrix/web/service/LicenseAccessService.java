package br.com.zentrix.web.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LicenseAccessService {
    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;

    public LicenseAccessService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
    }

    public void requireActive(String tenantId, String storeId, String path) {
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
        if (expired(tenantStatus)) {
            throw expiredPayment();
        }
        if (blocked(tenantStatus)) {
            throw new LicenseAccessException("ACCOUNT_BLOCKED", blockedMessage(tenant.get("blockReason")));
        }

        requireActiveStore(tenantId, storeId);

        List<Map<String, Object>> licenses = jdbcTemplate.queryForList("""
                SELECT status, plan_name AS planName, expires_at AS expiresAt
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
        if (expired(status)) {
            throw expiredPayment();
        }
        if (blocked(status)) {
            throw new LicenseAccessException("ACCOUNT_BLOCKED", "A assinatura desta loja esta bloqueada. Entre em contato com o suporte Zentrix para regularizar o acesso.");
        }
        Object expiresAt = license.get("expiresAt");
        if (expiresAt instanceof Timestamp timestamp && timestamp.toLocalDateTime().isBefore(LocalDateTime.now())) {
            if (!withinGracePeriod(tenantId, timestamp)) {
                throw expiredPayment();
            }
        }
        String plan = String.valueOf(license.getOrDefault("planName", ""));
        if (basicPlan(plan) && appGestaoPath(path)) {
            throw new LicenseAccessException("PLAN_UPGRADE_REQUIRED", "Seu plano Basico inclui somente o PDV. Para acessar o AppGestao, altere para o plano Intermediario ou Pro.");
        }
    }

    private void requireActiveStore(String tenantId, String storeId) {
        String store = storeId == null ? "" : storeId.trim();
        if (store.isBlank() || "WEB".equalsIgnoreCase(store) || "all".equalsIgnoreCase(store)) {
            return;
        }
        List<Map<String, Object>> stores = jdbcTemplate.queryForList("""
                SELECT status, block_reason AS blockReason
                FROM tenant_stores
                WHERE tenant_id = ? AND id = ?
                LIMIT 1
                """, tenantId, store);
        if (stores.isEmpty()) {
            return;
        }
        String status = String.valueOf(stores.get(0).getOrDefault("status", "ACTIVE"));
        if (expired(status)) {
            throw expiredPayment();
        }
        if (blocked(status)) {
            String reason = String.valueOf(stores.get(0).getOrDefault("blockReason", "")).trim();
            String message = reason.isBlank()
                    ? "Esta loja esta inativa ou bloqueada. Entre em contato com o suporte Zentrix para regularizar o acesso."
                    : "Esta loja esta bloqueada: " + reason + ". Entre em contato com o suporte Zentrix para regularizar o acesso.";
            throw new LicenseAccessException("STORE_BLOCKED", message);
        }
    }

    private LicenseAccessException expiredPayment() {
        return new LicenseAccessException(
                "PAYMENT_EXPIRED",
                "O pagamento da assinatura expirou. Prossiga para o pagamento para renovar o acesso ao AppGestao."
        );
    }

    private boolean withinGracePeriod(String tenantId, Timestamp expiresAt) {
        int days = jdbcTemplate.queryForList("""
                SELECT COALESCE((SELECT grace_days FROM billing_settings WHERE tenant_id = ?), 3) AS graceDays
                """, tenantId).stream()
                .findFirst()
                .map(row -> row.get("graceDays"))
                .filter(Number.class::isInstance)
                .map(Number.class::cast)
                .map(Number::intValue)
                .map(value -> Math.max(0, Math.min(value, 30)))
                .orElse(3);
        return !expiresAt.toLocalDateTime().plusDays(days).isBefore(LocalDateTime.now());
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
                || value.startsWith("/api/auth")
                || value.startsWith("/api/billing");
    }

    private boolean appGestaoPath(String path) {
        String value = path == null ? "" : path;
        return value.startsWith("/api/")
                && !value.startsWith("/api/pdv/")
                && !value.startsWith("/api/sync/")
                && !isAdminPath(value);
    }

    private boolean basicPlan(String plan) {
        String value = plan == null ? "" : plan.trim().toUpperCase();
        return value.equals("BASICO") || value.equals("BASIC");
    }

    private boolean blocked(String status) {
        String value = status == null ? "" : status.trim().toUpperCase();
        return value.equals("BLOCKED")
                || value.equals("SUSPENDED")
                || value.equals("CANCELLED")
                || value.equals("CANCELED")
                || value.equals("INACTIVE");
    }

    private boolean expired(String status) {
        return "EXPIRED".equalsIgnoreCase(status == null ? "" : status.trim());
    }
}
