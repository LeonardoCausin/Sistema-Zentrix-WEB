package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AuditServiceTest {

    @Test
    void webAuditUsesNonNullDeviceIdentity() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        AuditService service = new AuditService(jdbcTemplate, new NoopInitializer());

        service.record("tenant-1", "store-1", null, "WEB", "admin", "CLIENT_BLOCKED",
                "tenants", "tenant-1", "Bloqueado", "ALERTA", null, null,
                "Inadimplencia", "APPGESTAO", "127.0.0.1", "ADMIN");

        assertEquals("WEB", jdbcTemplate.lastUpdateArgs[2]);
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        Object[] lastUpdateArgs;

        @Override
        public int update(String sql, Object... args) {
            lastUpdateArgs = args;
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(Integer.valueOf(1));
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
}
