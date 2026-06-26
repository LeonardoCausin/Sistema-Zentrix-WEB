package br.com.zentrix.web.service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;

    public AuditService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
    }

    public void info(String action, String entityType, String entityId, String details) {
        record("legacy", "WEB", null, "WEB", null, action, entityType, entityId, details, "INFO", null, null, null, "API", null, null);
    }

    public void recordCurrent(String action, String entityType, String entityId, String details, String riskLevel, String reason) {
        AuthTokenService.SessionToken session = AuthContext.current().orElse(null);
        record(
                session == null ? "legacy" : session.tenantId(),
                "WEB",
                null,
                "WEB",
                session == null ? null : session.username(),
                action,
                entityType,
                entityId,
                details,
                riskLevel,
                null,
                null,
                reason,
                "APPGESTAO",
                null,
                session == null ? null : session.role()
        );
    }

    public void record(
            String tenantId,
            String storeId,
            String deviceId,
            String sourceId,
            String user,
            String action,
            String entityType,
            String entityId,
            String details,
            String riskLevel,
            String previousValue,
            String newValue,
            String reason,
            String origin,
            String ipAddress,
            String userRole
    ) {
        initializer.ensureReady();
        jdbcTemplate.update("""
                INSERT INTO audit_log
                    (tenant_id, store_id, device_id, source_id, id, usuario, acao, entity_type, entity_id, details, created_at,
                     risk_level, previous_value, new_value, reason, origin, ip_address, user_role)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    details = VALUES(details),
                    risk_level = VALUES(risk_level),
                    previous_value = VALUES(previous_value),
                    new_value = VALUES(new_value),
                    reason = VALUES(reason),
                    origin = VALUES(origin),
                    ip_address = VALUES(ip_address),
                    user_role = VALUES(user_role)
                """,
                safe(tenantId, "legacy"),
                safe(storeId, "WEB"),
                deviceId,
                safe(sourceId, "WEB"),
                nextAuditId(safe(tenantId, "legacy"), safe(storeId, "WEB")),
                user,
                safe(action, "INFO"),
                entityType,
                entityId,
                details,
                Timestamp.valueOf(LocalDateTime.now()),
                safe(riskLevel, "INFO"),
                previousValue,
                newValue,
                reason,
                safe(origin, "API"),
                ipAddress,
                userRole
        );
    }

    public List<Map<String, Object>> latest(String tenantId, String storeId, int limit) {
        initializer.ensureReady();
        return jdbcTemplate.query("""
                SELECT tenant_id, store_id, source_id, usuario, acao, entity_type, entity_id, details,
                       risk_level, reason, origin, created_at
                FROM audit_log
                WHERE tenant_id = ? AND (? IS NULL OR store_id = ?)
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("storeId", rs.getString("store_id"));
            row.put("sourceId", rs.getString("source_id"));
            row.put("user", rs.getString("usuario"));
            row.put("action", rs.getString("acao"));
            row.put("entityType", rs.getString("entity_type"));
            row.put("entityId", rs.getString("entity_id"));
            row.put("details", rs.getString("details"));
            row.put("riskLevel", rs.getString("risk_level"));
            row.put("reason", rs.getString("reason"));
            row.put("origin", rs.getString("origin"));
            row.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toString());
            return row;
        }, tenantId, storeId, storeId, Math.max(1, Math.min(limit, 100)));
    }

    private int nextAuditId(String tenantId, String storeId) {
        Integer next = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(id), 0) + 1
                FROM audit_log
                WHERE tenant_id = ? AND store_id = ?
                """, Integer.class, tenantId, storeId);
        return next == null ? 1 : next;
    }

    private String safe(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
