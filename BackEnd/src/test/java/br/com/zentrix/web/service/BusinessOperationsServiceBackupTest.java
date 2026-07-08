package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

class BusinessOperationsServiceBackupTest {
    @TempDir
    Path tempDir;

    private FakeJdbcTemplate jdbcTemplate;
    private BusinessOperationsService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new FakeJdbcTemplate();
        NoopInitializer initializer = new NoopInitializer();
        service = new BusinessOperationsService(
                jdbcTemplate,
                initializer,
                new PermissionService(),
                new NoopAuditService(jdbcTemplate, initializer),
                new SettingsService(jdbcTemplate, initializer),
                new AuthTokenService(),
                new WebChangeOutboxService(jdbcTemplate, initializer, new ObjectMapper()),
                new PanelCacheService(),
                tempDir.toString()
        );

        AuthContext.set(new AuthTokenService.SessionToken(
                "admin",
                "Administrador",
                "ADMIN",
                "tenant-1",
                "store-a",
                "WEB",
                Set.of(),
                Instant.now(),
                Instant.now().plusSeconds(3600)
        ));
    }

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void manualBackupCreatesPhysicalSqlFileScopedByStoreAndValidatesChecksum() throws Exception {
        Map<String, Object> result = service.manualBackup("tenant-1", "store-a");

        assertEquals("CONCLUIDO", result.get("status"));
        Path file = tempDir
                .resolve("tenant-1")
                .resolve("store-a")
                .resolve(String.valueOf(result.get("fileName")));
        assertTrue(Files.isRegularFile(file));

        String content = Files.readString(file);
        assertTrue(content.contains("Produto Loja A"));
        assertFalse(content.contains("Produto Loja B"));
        assertTrue(content.contains("Configuração global"));

        jdbcTemplate.downloadRows = List.of(Map.of(
                "filePath", file.toString(),
                "fileName", result.get("fileName"),
                "checksum", result.get("checksum"),
                "status", "CONCLUIDO"
        ));

        assertEquals(file, service.validatedBackupFile("tenant-1", 101L));
    }

    @Test
    void restoreWithConfirmationCreatesStagingWithoutApplyingProductionChanges() throws Exception {
        Map<String, Object> backup = service.manualBackup("tenant-1", "store-a");
        Path file = tempDir
                .resolve("tenant-1")
                .resolve("store-a")
                .resolve(String.valueOf(backup.get("fileName")));
        jdbcTemplate.downloadRows = List.of(Map.ofEntries(
                Map.entry("id", 101L),
                Map.entry("tenantId", "tenant-1"),
                Map.entry("storeId", "store-a"),
                Map.entry("sourceId", "WEB"),
                Map.entry("deviceId", ""),
                Map.entry("status", "CONCLUIDO"),
                Map.entry("totalRows", 2),
                Map.entry("fileName", backup.get("fileName")),
                Map.entry("filePath", file.toString()),
                Map.entry("checksum", backup.get("checksum")),
                Map.entry("createdAt", "2026-07-08 08:00:00"),
                Map.entry("finishedAt", "2026-07-08 08:00:01"),
                Map.entry("message", "Backup manual concluído.")
        ));

        Map<String, Object> response = service.restoreBackup("tenant-1", 101L, Map.of("confirmation", "RESTAURAR BACKUP 101"));

        assertEquals("RESTORE_STAGED", response.get("status"));
        assertEquals(false, response.get("restoreExecuted"));
        assertEquals(1, jdbcTemplate.stagingUpdates);
        assertTrue(Number.class.cast(response.get("totalRows")).intValue() > 0);
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        List<Map<String, Object>> downloadRows = List.of();
        int stagingUpdates;

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("backup_restore_staging")) {
                stagingUpdates++;
            }
            return 1;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            if (sql.contains("LAST_INSERT_ID")) {
                return (T) Long.valueOf(101L);
            }
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            if (sql.contains("LAST_INSERT_ID")) {
                return (T) Long.valueOf(101L);
            }
            return null;
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("FROM backup_runs")) {
                return downloadRows;
            }
            if (sql.contains("FROM `tenant_stores`")) {
                return List.of(row("tenant_id", "tenant-1", "id", "store-a", "name", "Loja A"));
            }
            if (sql.contains("FROM `app_settings`")) {
                return List.of(row("tenant_id", "tenant-1", "store_id", "all", "setting_key", "empresa.nome", "setting_value", "Configuração global"));
            }
            if (sql.contains("FROM `products`") && args.length > 1 && "store-a".equals(args[1])) {
                return List.of(row("tenant_id", "tenant-1", "store_id", "store-a", "code", "A1", "description", "Produto Loja A"));
            }
            if (sql.contains("FROM `products`") && args.length > 1 && "store-b".equals(args[1])) {
                return List.of(row("tenant_id", "tenant-1", "store_id", "store-b", "code", "B1", "description", "Produto Loja B"));
            }
            return List.of();
        }

        private static Map<String, Object> row(Object... values) {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < values.length; i += 2) {
                row.put(String.valueOf(values[i]), values[i + 1]);
            }
            return row;
        }
    }

    private static class NoopInitializer extends WebDatabaseInitializer {
        NoopInitializer() {
            super(null, null);
        }

        @Override
        public void ensureReady() {
        }
    }

    private static class NoopAuditService extends AuditService {
        NoopAuditService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer) {
            super(jdbcTemplate, initializer);
        }

        @Override
        public void recordCurrent(String action, String entityType, String entityId, String details, String riskLevel, String reason) {
        }
    }
}
