package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.ActivateStoreRequest;
import br.com.zentrix.web.dto.ActivationCodeRequest;
import br.com.zentrix.web.dto.ProvisionStoreRequest;
import br.com.zentrix.web.dto.ProvisionTenantRequest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProvisioningService {
    private static final int DEFAULT_CODE_TTL_MINUTES = 1440;
    private static final int MAX_CODE_TTL_MINUTES = 10080;

    private final SecureRandom secureRandom = new SecureRandom();
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final WebDatabaseInitializer initializer;

    public ProvisioningService(JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate, WebDatabaseInitializer initializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.initializer = initializer;
    }

    public Map<String, Object> bootstrap(ProvisionTenantRequest request) {
        initializer.ensureReady();
        validateBootstrap(request);
        return transactionTemplate.execute(status -> {
            String tenantId = uuid();
            String storeId = uuid();
            String deviceId = optional(request.deviceId(), uuid());
            String companyName = required(request.companyName(), "companyName");
            String storeName = optional(request.storeName(), "Loja matriz");
            String sourceId = optional(request.sourceId(), "LOJA-" + storeId.substring(0, 8).toUpperCase());
            String deviceName = optional(request.deviceName(), deviceId);
            String adminUsername = required(request.adminUsername(), "adminUsername");
            String adminDisplayName = optional(request.adminDisplayName(), adminUsername);
            String passwordHash = passwordHash(request.adminPasswordHash(), request.adminPassword());

            upsertTenant(tenantId, companyName, request.document());
            upsertStore(tenantId, storeId, storeName, sourceId);
            upsertDevice(tenantId, storeId, deviceId, deviceName, sourceId);
            upsertAdmin(tenantId, storeId, deviceId, sourceId, adminUsername, adminDisplayName, passwordHash);

            return response(tenantId, companyName, storeId, storeName, deviceId, deviceName, sourceId, true);
        });
    }

    public Map<String, Object> addStore(ProvisionStoreRequest request) {
        initializer.ensureReady();
        String tenantId = required(request.tenantId(), "tenantId");
        if (!tenantExists(tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado");
        }
        return transactionTemplate.execute(status -> {
            String storeId = uuid();
            String deviceId = optional(request.deviceId(), uuid());
            String storeName = optional(request.storeName(), "Nova loja");
            String sourceId = optional(request.sourceId(), "LOJA-" + storeId.substring(0, 8).toUpperCase());
            String deviceName = optional(request.deviceName(), deviceId);
            String tenantName = tenantName(tenantId);

            upsertStore(tenantId, storeId, storeName, sourceId);
            upsertDevice(tenantId, storeId, deviceId, deviceName, sourceId);

            return response(tenantId, tenantName, storeId, storeName, deviceId, deviceName, sourceId, false);
        });
    }

    public Map<String, Object> createActivationCode(ActivationCodeRequest request) {
        initializer.ensureReady();
        return transactionTemplate.execute(status -> {
            String tenantId = optional(request.tenantId(), null);
            String tenantName;
            if (tenantId == null) {
                tenantId = uuid();
                tenantName = optional(request.companyName(), "Novo cliente");
                upsertTenant(tenantId, tenantName, request.document());
            } else {
                if (!tenantExists(tenantId)) {
                    throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente não encontrado");
                }
                tenantName = tenantName(tenantId);
            }

            String storeId = uuid();
            String storeName = optional(request.storeName(), "Nova loja");
            String sourceId = optional(request.sourceId(), "LOJA-" + storeId.substring(0, 8).toUpperCase());
            int ttlMinutes = ttlMinutes(request.expiresMinutes());
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttlMinutes);
            String code = uniqueCode();

            upsertStore(tenantId, storeId, storeName, sourceId);
            jdbcTemplate.update("""
                    INSERT INTO activation_codes (code, tenant_id, store_id, store_name, source_id, status, expires_at)
                    VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                    """, code, tenantId, storeId, storeName, sourceId, Timestamp.valueOf(expiresAt));

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("code", code);
            response.put("tenantId", tenantId);
            response.put("tenantName", tenantName);
            response.put("storeId", storeId);
            response.put("storeName", storeName);
            response.put("sourceId", sourceId);
            response.put("expiresAt", expiresAt.toString());
            response.put("expiresMinutes", ttlMinutes);
            response.put("status", "ACTIVE");
            return response;
        });
    }

    public Map<String, Object> activateCode(ActivateStoreRequest request) {
        initializer.ensureReady();
        String code = required(request.code(), "code").replaceAll("[^0-9A-Za-z]", "").toUpperCase();
        return transactionTemplate.execute(status -> {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                    SELECT ac.code, ac.tenant_id, ac.store_id, ac.store_name, ac.source_id, ac.status, ac.expires_at,
                           COALESCE(t.name, ac.tenant_id) AS tenant_name
                    FROM activation_codes ac
                    LEFT JOIN tenants t ON t.id = ac.tenant_id
                    WHERE ac.code = ?
                    LIMIT 1
                    """, code);
            if (rows.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Código de ativação inválido");
            }
            Map<String, Object> row = rows.get(0);
            String currentStatus = String.valueOf(row.get("status"));
            LocalDateTime expiresAt = toLocalDateTime(row.get("expires_at"));
            if (!"ACTIVE".equalsIgnoreCase(currentStatus)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código de ativação já utilizado");
            }
            if (expiresAt == null || expiresAt.isBefore(LocalDateTime.now())) {
                jdbcTemplate.update("UPDATE activation_codes SET status = 'EXPIRED' WHERE code = ?", code);
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Código de ativação expirado");
            }

            String tenantId = String.valueOf(row.get("tenant_id"));
            String tenantName = String.valueOf(row.get("tenant_name"));
            String storeId = String.valueOf(row.get("store_id"));
            String storeName = String.valueOf(row.get("store_name"));
            String sourceId = optional(request.sourceId(), String.valueOf(row.get("source_id")));
            String deviceId = optional(request.deviceId(), uuid());
            String deviceName = optional(request.deviceName(), deviceId);

            upsertStore(tenantId, storeId, storeName, sourceId);
            upsertDevice(tenantId, storeId, deviceId, deviceName, sourceId);
            jdbcTemplate.update("""
                    UPDATE activation_codes
                    SET status = 'USED', used_at = CURRENT_TIMESTAMP, used_device_id = ?
                    WHERE code = ?
                    """, deviceId, code);

            return response(tenantId, tenantName, storeId, storeName, deviceId, deviceName, sourceId, false);
        });
    }

    private void validateBootstrap(ProvisionTenantRequest request) {
        required(request.companyName(), "companyName");
        required(request.adminUsername(), "adminUsername");
        passwordHash(request.adminPasswordHash(), request.adminPassword());
    }

    private void upsertTenant(String tenantId, String name, String document) {
        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, document, status)
                VALUES (?, ?, ?, 'ACTIVE')
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    document = VALUES(document),
                    status = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP
                """, tenantId, name, blankToNull(document));
    }

    private void upsertStore(String tenantId, String storeId, String name, String sourceId) {
        jdbcTemplate.update("""
                INSERT INTO tenant_stores (tenant_id, id, name, source_id, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    source_id = VALUES(source_id),
                    status = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP
                """, tenantId, storeId, name, sourceId);
    }

    private void upsertDevice(String tenantId, String storeId, String deviceId, String name, String sourceId) {
        jdbcTemplate.update("""
                INSERT INTO tenant_devices (tenant_id, store_id, id, name, source_id, status, last_seen_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    source_id = VALUES(source_id),
                    status = 'ACTIVE',
                    last_seen_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                """, tenantId, storeId, deviceId, name, sourceId);
    }

    private void upsertAdmin(String tenantId, String storeId, String deviceId, String sourceId, String username, String displayName, String passwordHash) {
        jdbcTemplate.update("""
                INSERT INTO users (tenant_id, store_id, device_id, source_id, username, password, display_name, role, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ADMIN', TRUE)
                ON DUPLICATE KEY UPDATE
                    password = VALUES(password),
                    display_name = VALUES(display_name),
                    role = 'ADMIN',
                    active = TRUE
                """, tenantId, storeId, deviceId, sourceId, username, passwordHash, displayName);
    }

    private boolean tenantExists(String tenantId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Long.class, tenantId);
        return count != null && count > 0;
    }

    private String tenantName(String tenantId) {
        return jdbcTemplate.query("SELECT name FROM tenants WHERE id = ? LIMIT 1", (rs, rowNum) -> rs.getString(1), tenantId)
                .stream()
                .findFirst()
                .orElse(tenantId);
    }

    private Map<String, Object> response(
            String tenantId,
            String tenantName,
            String storeId,
            String storeName,
            String deviceId,
            String deviceName,
            String sourceId,
            boolean firstAdminCreated
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenantId", tenantId);
        response.put("tenantName", tenantName);
        response.put("storeId", storeId);
        response.put("storeName", storeName);
        response.put("deviceId", deviceId);
        response.put("deviceName", deviceName);
        response.put("sourceId", sourceId);
        response.put("firstAdminCreated", firstAdminCreated);
        response.put("status", "ACTIVE");
        response.put("createdAt", OffsetDateTime.now().toString());
        return response;
    }

    private String passwordHash(String providedHash, String plainPassword) {
        if (providedHash != null && !providedHash.isBlank()) {
            String hash = providedHash.trim();
            if (hash.startsWith("$2a$") || hash.startsWith("$2b$") || hash.startsWith("$2y$")) {
                return hash;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "adminPasswordHash deve ser BCrypt");
        }
        if (plainPassword == null || plainPassword.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe adminPasswordHash ou adminPassword");
        }
        return BCrypt.hashpw(plainPassword, BCrypt.gensalt(12));
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " é obrigatório");
        }
        return value.trim();
    }

    private String optional(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }

    private LocalDateTime toLocalDateTime(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().atStartOfDay();
        }
        if (value instanceof java.util.Date date) {
            return new Timestamp(date.getTime()).toLocalDateTime();
        }
        if (value instanceof CharSequence text) {
            return LocalDateTime.parse(text.toString().replace(' ', 'T'));
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Tipo invalido em activation_codes.expires_at: " + value.getClass().getName());
    }

    private String uniqueCode() {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = String.format("%06d", secureRandom.nextInt(1_000_000));
            Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM activation_codes WHERE code = ?", Long.class, code);
            if (count == null || count == 0) {
                return code;
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Não foi possível gerar código de ativação");
    }

    private int ttlMinutes(Integer requested) {
        if (requested == null || requested <= 0) {
            return DEFAULT_CODE_TTL_MINUTES;
        }
        return Math.min(requested, MAX_CODE_TTL_MINUTES);
    }
}
