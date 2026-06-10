package br.com.zentrix.web.service;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HealthService {

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;

    public HealthService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
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
        } catch (Exception e) {
            response.put("status", "DEGRADED");
            response.put("database", "DOWN");
            response.put("message", e.getMessage());
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
}
