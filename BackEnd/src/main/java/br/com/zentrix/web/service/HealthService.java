package br.com.zentrix.web.service;

import br.com.zentrix.web.config.DatabaseConfig.DatabaseSettings;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final DatabaseSettings databaseSettings;
    private final boolean exposeDetails;

    public HealthService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            DatabaseSettings databaseSettings,
            @Value("${zentrix.health.expose-details:false}") boolean exposeDetails
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.databaseSettings = databaseSettings;
        this.exposeDetails = exposeDetails;
    }

    public Map<String, Object> health() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("service", "zentrix-web-api");
        response.put("timestamp", OffsetDateTime.now().toString());
        try {
            initializer.ensureReady();
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            response.put("status", "UP");
            response.put("database", "UP");
            response.put("lastSync", lastSync());
            response.put("apiVersion", "0.1.0-SNAPSHOT");
            if (exposeDetails) {
                response.put("databaseDetails", Map.of("status", "UP", "name", databaseSettings.getName()));
                response.put("totalTenants", count("tenants"));
                response.put("totalStores", count("tenant_stores"));
                response.put("serverTime", OffsetDateTime.now().toString());
            }
        } catch (Exception e) {
            response.put("status", "DEGRADED");
            response.put("database", "DOWN");
            if (exposeDetails) {
                response.put("databaseDetails", Map.of("status", "DOWN", "name", databaseSettings.getName()));
                response.put("message", e.getMessage());
            }
        }
        return response;
    }

    private Object lastSync() {
        List<String> rows = jdbcTemplate.query("""
                SELECT CAST(received_at AS CHAR)
                FROM sync_runs
                WHERE status = 'SUCCESS'
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getString(1));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Long count(String table) {
        Long value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return value == null ? 0L : value;
    }
}
