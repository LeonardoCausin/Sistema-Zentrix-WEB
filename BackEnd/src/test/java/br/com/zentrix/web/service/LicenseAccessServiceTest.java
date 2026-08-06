package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LicenseAccessServiceTest {
    private FakeJdbcTemplate jdbcTemplate;
    private LicenseAccessService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new FakeJdbcTemplate();
        service = new LicenseAccessService(jdbcTemplate, new NoopInitializer());
    }

    @Test
    void identifiesExpiredPaymentForExpiredLicenseDate() {
        jdbcTemplate.addQueryResult(List.of(Map.of("status", "ACTIVE")));
        jdbcTemplate.addQueryResult(List.of(Map.of(
                "status", "ACTIVE",
                "planName", "INTERMEDIARIO",
                "expiresAt", Timestamp.valueOf(LocalDateTime.now().minusMinutes(1))
        )));

        LicenseAccessException error = assertThrows(
                LicenseAccessException.class,
                () -> service.requireActive("tenant-1", "WEB", "/api/dashboard")
        );

        assertEquals("PAYMENT_EXPIRED", error.reasonCode());
    }

    @Test
    void blocksOnlyTheInactiveStoreInSession() {
        jdbcTemplate.addQueryResult(List.of(Map.of("status", "ACTIVE")));
        jdbcTemplate.addQueryResult(List.of(Map.of("status", "INACTIVE")));

        LicenseAccessException error = assertThrows(
                LicenseAccessException.class,
                () -> service.requireActive("tenant-1", "store-1", "/api/dashboard")
        );

        assertEquals("STORE_BLOCKED", error.reasonCode());
    }

    @Test
    void allowsAuthenticatedBillingWhenLicenseIsExpired() {
        service.requireActive("tenant-1", "WEB", "/api/billing/checkout");

        assertEquals(0, jdbcTemplate.queryResults.size());
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        private final ArrayDeque<List<Map<String, Object>>> queryResults = new ArrayDeque<>();

        void addQueryResult(List<Map<String, Object>> rows) {
            queryResults.add(rows);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (queryResults.isEmpty()) {
                throw new AssertionError("Unexpected queryForList call: " + sql);
            }
            return queryResults.removeFirst();
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
