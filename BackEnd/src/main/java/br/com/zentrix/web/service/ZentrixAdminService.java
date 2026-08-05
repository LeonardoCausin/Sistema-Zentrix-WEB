package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.ActivationCodeRequest;
import br.com.zentrix.web.dto.ProvisionTenantRequest;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ZentrixAdminService {
    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final ProvisioningService provisioningService;
    private final PanelCacheService panelCacheService;
    private final AuditService auditService;

    public ZentrixAdminService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            ProvisioningService provisioningService,
            PanelCacheService panelCacheService,
            AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.provisioningService = provisioningService;
        this.panelCacheService = panelCacheService;
        this.auditService = auditService;
    }

    public Map<String, Object> overview() {
        initializer.ensureReady();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clients", number("SELECT COUNT(*) FROM tenants"));
        response.put("activeClients", number("SELECT COUNT(*) FROM tenants WHERE UPPER(status) = 'ACTIVE'"));
        response.put("blockedClients", number("SELECT COUNT(*) FROM tenants WHERE UPPER(status) IN ('BLOCKED', 'SUSPENDED')"));
        response.put("activeSubscriptions", number("""
                SELECT COUNT(*)
                FROM licenses l
                JOIN (
                    SELECT tenant_id, MAX(id) AS id
                    FROM licenses
                    GROUP BY tenant_id
                ) latest ON latest.id = l.id
                WHERE UPPER(l.status) = 'ACTIVE'
                """));
        response.put("expiringSoon", number("""
                SELECT COUNT(*)
                FROM licenses l
                JOIN (
                    SELECT tenant_id, MAX(id) AS id
                    FROM licenses
                    GROUP BY tenant_id
                ) latest ON latest.id = l.id
                WHERE UPPER(l.status) = 'ACTIVE'
                  AND l.expires_at IS NOT NULL
                  AND l.expires_at BETWEEN NOW() AND DATE_ADD(NOW(), INTERVAL 7 DAY)
                """));
        response.put("stores", number("SELECT COUNT(*) FROM tenant_stores"));
        response.put("devices", number("SELECT COUNT(*) FROM tenant_devices"));
        response.put("recentClients", clients("", "all", 8));
        return response;
    }

    public List<Map<String, Object>> clients(String search, String status, int limit) {
        initializer.ensureReady();
        String q = "%" + text(search).toLowerCase() + "%";
        String normalizedStatus = text(status);
        return jdbcTemplate.queryForList("""
                SELECT t.id AS tenantId, t.name, t.document, t.status,
                       t.block_reason AS blockReason, t.blocked_at AS blockedAt, t.blocked_by AS blockedBy,
                       t.created_at AS createdAt, t.updated_at AS updatedAt,
                       l.id AS licenseId, l.plan_name AS planName, l.status AS licenseStatus,
                       l.starts_at AS startsAt, l.expires_at AS expiresAt, l.max_stores AS maxStores, l.max_devices AS maxDevices,
                       COALESCE(stores.count, 0) AS stores,
                       COALESCE(devices.count, 0) AS devices,
                       COALESCE(users.count, 0) AS users
                FROM tenants t
                LEFT JOIN (
                    SELECT l1.*
                    FROM licenses l1
                    JOIN (
                        SELECT tenant_id, MAX(id) AS id
                        FROM licenses
                        GROUP BY tenant_id
                    ) latest ON latest.id = l1.id
                ) l ON l.tenant_id = t.id
                LEFT JOIN (SELECT tenant_id, COUNT(*) AS count FROM tenant_stores GROUP BY tenant_id) stores ON stores.tenant_id = t.id
                LEFT JOIN (SELECT tenant_id, COUNT(*) AS count FROM tenant_devices GROUP BY tenant_id) devices ON devices.tenant_id = t.id
                LEFT JOIN (SELECT tenant_id, COUNT(*) AS count FROM users GROUP BY tenant_id) users ON users.tenant_id = t.id
                WHERE (? = '' OR LOWER(t.name) LIKE ? OR LOWER(COALESCE(t.document, '')) LIKE ? OR LOWER(t.id) LIKE ?)
                  AND (? = 'all' OR UPPER(t.status) = UPPER(?))
                ORDER BY t.created_at DESC, t.name
                LIMIT ?
                """, q.equals("%%") ? "" : q, q, q, q, normalizedStatus.isBlank() ? "all" : normalizedStatus, normalizedStatus, safeLimit(limit));
    }

    public Map<String, Object> client(String tenantId) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        List<Map<String, Object>> tenants = jdbcTemplate.queryForList("""
                SELECT id AS tenantId, name, document, status, block_reason AS blockReason,
                       blocked_at AS blockedAt, blocked_by AS blockedBy,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM tenants
                WHERE id = ?
                """, tenant);
        if (tenants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente nao encontrado.");
        }
        Map<String, Object> response = new LinkedHashMap<>(tenants.get(0));
        response.put("licenses", jdbcTemplate.queryForList("""
                SELECT id, plan_name AS planName, status, starts_at AS startsAt, expires_at AS expiresAt,
                       max_stores AS maxStores, max_devices AS maxDevices, created_at AS createdAt, updated_at AS updatedAt
                FROM licenses
                WHERE tenant_id = ?
                ORDER BY id DESC
                """, tenant));
        response.put("stores", jdbcTemplate.queryForList("""
                SELECT id, name, source_id AS sourceId, status, created_at AS createdAt, updated_at AS updatedAt
                FROM tenant_stores
                WHERE tenant_id = ?
                ORDER BY created_at DESC, name
                """, tenant));
        response.put("devices", jdbcTemplate.queryForList("""
                SELECT store_id AS storeId, id, name, source_id AS sourceId, status, last_seen_at AS lastSeenAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM tenant_devices
                WHERE tenant_id = ?
                ORDER BY last_seen_at DESC, created_at DESC
                """, tenant));
        response.put("activationCodes", jdbcTemplate.queryForList("""
                SELECT code, store_id AS storeId, store_name AS storeName, source_id AS sourceId, status,
                       expires_at AS expiresAt, used_at AS usedAt, used_device_id AS usedDeviceId, created_at AS createdAt
                FROM activation_codes
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 20
                """, tenant));
        return response;
    }

    @Transactional
    public Map<String, Object> createClient(Map<String, Object> request) {
        initializer.ensureReady();
        String companyName = required(value(request, "companyName"), "companyName");
        String adminUsername = required(value(request, "adminUsername"), "adminUsername");
        String adminPassword = required(value(request, "adminPassword"), "adminPassword");
        Map<String, Object> created = provisioningService.bootstrap(new ProvisionTenantRequest(
                companyName,
                value(request, "document"),
                defaultValue(value(request, "storeName"), "Loja matriz"),
                value(request, "sourceId"),
                value(request, "deviceId"),
                value(request, "deviceName"),
                adminUsername,
                defaultValue(value(request, "adminDisplayName"), adminUsername),
                adminPassword,
                null
        ));
        String tenantId = String.valueOf(created.get("tenantId"));
        Map<String, Object> license = createLicense(tenantId, request);
        auditService.recordCurrent("ZENTRIX_ADMIN_CLIENT_CREATED", "tenants", tenantId,
                "Cliente criado pelo painel administrativo Zentrix.", "ALERTA", value(request, "reason"));
        panelCacheService.clear();
        return Map.of("client", created, "license", license);
    }

    @Transactional
    public Map<String, Object> createLicense(String tenantId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        String plan = defaultValue(value(request, "planName"), "BASICO");
        String status = defaultValue(value(request, "licenseStatus"), defaultValue(value(request, "status"), "ACTIVE"));
        Timestamp startsAt = timestampOrNull(value(request, "startsAt"));
        if (startsAt == null) {
            startsAt = Timestamp.valueOf(LocalDateTime.now());
        }
        Timestamp expiresAt = timestampOrNull(value(request, "expiresAt"));
        int maxStores = intValue(request.get("maxStores"), 1);
        int maxDevices = intValue(request.get("maxDevices"), 1);
        jdbcTemplate.update("""
                INSERT INTO licenses (tenant_id, plan_name, status, starts_at, expires_at, max_stores, max_devices)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenant, plan, status, startsAt, expiresAt, maxStores, maxDevices);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (boolValue(request == null ? null : request.get("activateClient")) && "ACTIVE".equalsIgnoreCase(status)) {
            jdbcTemplate.update("""
                    UPDATE tenants
                    SET status = 'ACTIVE', block_reason = NULL, blocked_at = NULL, blocked_by = NULL
                    WHERE id = ?
                    """, tenant);
        }
        auditService.recordCurrent("ZENTRIX_ADMIN_LICENSE_CREATED", "licenses", String.valueOf(id),
                "Assinatura criada/renovada pelo painel administrativo Zentrix.", "ALERTA", value(request, "reason"));
        panelCacheService.clear();
        return Map.of(
                "id", id == null ? 0 : id,
                "tenantId", tenant,
                "planName", plan,
                "status", status,
                "startsAt", startsAt.toString(),
                "expiresAt", expiresAt == null ? "" : expiresAt.toString(),
                "maxStores", maxStores,
                "maxDevices", maxDevices
        );
    }

    @Transactional
    public Map<String, Object> updateClientStatus(String tenantId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        String status = required(value(request, "status"), "status").toUpperCase();
        if (!validStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status invalido.");
        }
        boolean restricted = restrictedStatus(status);
        String reason = text(value(request, "reason"));
        if (restricted && reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo do bloqueio.");
        }
        String operator = AuthContext.current()
                .map(AuthTokenService.SessionToken::username)
                .orElse("zentrix-admin");
        int updated = jdbcTemplate.update("""
                UPDATE tenants
                SET status = ?, block_reason = ?, blocked_at = ?, blocked_by = ?
                WHERE id = ?
                """, status, restricted ? reason : null, restricted ? Timestamp.valueOf(LocalDateTime.now()) : null, restricted ? operator : null, tenant);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente nao encontrado.");
        }
        if (request != null && Boolean.TRUE.equals(request.get("updateLicense"))) {
            jdbcTemplate.update("""
                    UPDATE licenses
                    SET status = ?
                    WHERE tenant_id = ?
                    ORDER BY id DESC
                    LIMIT 1
                    """, status, tenant);
        }
        auditService.recordCurrent("ZENTRIX_ADMIN_CLIENT_STATUS_UPDATED", "tenants", tenant,
                "Status do cliente alterado para " + status + ".", "CRITICO", reason);
        panelCacheService.clear();
        return Map.of(
                "tenantId", tenant,
                "status", status,
                "updated", updated,
                "blockReason", restricted ? reason : ""
        );
    }

    @Transactional
    public Map<String, Object> createActivationCode(String tenantId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        ensureTenantExists(tenant);
        Map<String, Object> code = provisioningService.createActivationCode(new ActivationCodeRequest(
                tenant,
                null,
                null,
                defaultValue(value(request, "storeName"), "Nova loja"),
                value(request, "sourceId"),
                intValue(request.get("expiresMinutes"), 1440)
        ));
        auditService.recordCurrent("ZENTRIX_ADMIN_ACTIVATION_CODE_CREATED", "activation_codes", String.valueOf(code.get("code")),
                "Codigo de ativacao criado pelo painel administrativo Zentrix.", "ALERTA", value(request, "reason"));
        panelCacheService.clear();
        return code;
    }

    private void ensureTenantExists(String tenantId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Long.class, tenantId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente nao encontrado.");
        }
    }

    private long number(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private int safeLimit(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
    }

    private boolean validStatus(String status) {
        return List.of("ACTIVE", "BLOCKED", "SUSPENDED", "EXPIRED", "CANCELLED", "CANCELED", "INACTIVE").contains(status);
    }

    private boolean restrictedStatus(String status) {
        return !"ACTIVE".equalsIgnoreCase(status);
    }

    private String value(Map<String, Object> request, String key) {
        return request == null || request.get(key) == null ? "" : String.valueOf(request.get(key)).trim();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String required(String value, String field) {
        String text = text(value);
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe " + field + ".");
        }
        return text;
    }

    private String defaultValue(String value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private int intValue(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Numero invalido.");
        }
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value == null ? "" : value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "on".equalsIgnoreCase(text);
    }

    private Timestamp timestampOrNull(String value) {
        String text = text(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            if (text.length() == 10) {
                return Timestamp.valueOf(LocalDateTime.parse(text + "T23:59:59"));
            }
            return Timestamp.valueOf(LocalDateTime.parse(text.replace(' ', 'T')));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data invalida.");
        }
    }
}
