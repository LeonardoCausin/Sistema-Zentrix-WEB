package br.com.zentrix.web.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LicenseService {
    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;

    public LicenseService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
    }

    public Map<String, Object> current(String tenantId) {
        initializer.ensureReady();
        Integer configuredGraceDays = jdbcTemplate.queryForObject("""
                SELECT COALESCE((SELECT grace_days FROM billing_settings WHERE tenant_id = ?), 3)
                """, Integer.class, tenantId);
        int graceDays = configuredGraceDays == null ? 3 : configuredGraceDays;
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id, tenant_id, plan_name, status, starts_at, expires_at, max_stores, max_devices, created_at, updated_at
                FROM licenses
                WHERE tenant_id = ?
                ORDER BY created_at DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("planName", rs.getString("plan_name"));
            row.put("status", rs.getString("status"));
            row.put("startsAt", rs.getTimestamp("starts_at") == null ? null : rs.getTimestamp("starts_at").toString());
            row.put("expiresAt", rs.getTimestamp("expires_at") == null ? null : rs.getTimestamp("expires_at").toString());
            row.put("maxStores", rs.getInt("max_stores"));
            row.put("maxDevices", rs.getInt("max_devices"));
            row.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toString());
            row.put("updatedAt", rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toString());
            if (rs.getTimestamp("expires_at") != null && rs.getTimestamp("expires_at").toLocalDateTime().isBefore(LocalDateTime.now())) {
                LocalDateTime graceUntil = rs.getTimestamp("expires_at").toLocalDateTime().plusDays(graceDays);
                row.put("graceUntil", graceUntil.toString());
                row.put("accessStatus", graceUntil.isBefore(LocalDateTime.now()) ? "EXPIRED" : "GRACE_PERIOD");
            } else {
                row.put("accessStatus", rs.getString("status"));
            }
            return row;
        }, tenantId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("tenantId", tenantId);
        fallback.put("planName", "LEGACY");
        fallback.put("status", "ACTIVE");
        fallback.put("maxStores", 0);
        fallback.put("maxDevices", 0);
        fallback.put("legacy", true);
        return fallback;
    }

    public List<Map<String, Object>> devices(String tenantId) {
        initializer.ensureReady();
        return jdbcTemplate.query("""
                SELECT tenant_id, store_id, id, name, source_id, app_type, status, billable,
                       activated_at, deactivated_at, last_seen_at, created_at, updated_at
                FROM tenant_devices
                WHERE tenant_id = ?
                ORDER BY last_seen_at DESC, store_id, id
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("storeId", rs.getString("store_id"));
            row.put("id", rs.getString("id"));
            row.put("name", rs.getString("name"));
            row.put("sourceId", rs.getString("source_id"));
            row.put("appType", rs.getString("app_type"));
            row.put("status", rs.getString("status"));
            row.put("billable", rs.getBoolean("billable"));
            row.put("activatedAt", rs.getTimestamp("activated_at") == null ? null : rs.getTimestamp("activated_at").toString());
            row.put("deactivatedAt", rs.getTimestamp("deactivated_at") == null ? null : rs.getTimestamp("deactivated_at").toString());
            row.put("lastSeenAt", rs.getTimestamp("last_seen_at") == null ? null : rs.getTimestamp("last_seen_at").toString());
            row.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toString());
            row.put("updatedAt", rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toString());
            return row;
        }, tenantId);
    }
}
