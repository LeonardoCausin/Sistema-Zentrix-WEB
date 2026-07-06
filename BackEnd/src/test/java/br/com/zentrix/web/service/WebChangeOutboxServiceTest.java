package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import br.com.zentrix.web.dto.SyncAckRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class WebChangeOutboxServiceTest {
    private FakeJdbcTemplate jdbcTemplate;
    private WebChangeOutboxService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new FakeJdbcTemplate();
        service = new WebChangeOutboxService(jdbcTemplate, new NoopInitializer(), new ObjectMapper());
    }

    @Test
    void pullMarksPendingRowsAsDeliveredAndReturnsContract() {
        jdbcTemplate.pullRows = List.of(Map.ofEntries(
                Map.entry("id", 7L),
                Map.entry("tenant_id", "tenant-1"),
                Map.entry("store_id", "store-1"),
                Map.entry("source_id", "WEB"),
                Map.entry("target_source_id", "pdv-1"),
                Map.entry("target_device_id", "device-1"),
                Map.entry("entity_type", "PRODUCT"),
                Map.entry("entity_id", "P1"),
                Map.entry("operation", "PRODUCT_UPSERT"),
                Map.entry("contract_version", "2026-07-02"),
                Map.entry("payload_json", "{\"table\":\"products\",\"record\":{\"code\":\"P1\"}}"),
                Map.entry("status", "PENDING"),
                Map.entry("attempts", 0),
                Map.entry("error_count", 0),
                Map.entry("created_at", Timestamp.valueOf("2026-07-02 10:00:00"))
        ));

        Map<String, Object> response = service.pull("tenant-1", "store-1", 0, 100);

        assertEquals(1, response.get("count"));
        assertFalse((Boolean) response.get("hasMore"));
        assertEquals(1, jdbcTemplate.deliveredUpdates);
    }

    @Test
    void nonRetryableAckErrorMovesRowToDeadLetter() {
        jdbcTemplate.ackRows = List.of(Map.of(
                "id", 7L,
                "error_count", 0
        ));

        Map<String, Object> response = service.ack("tenant-1", "store-1",
                new SyncAckRequest(List.of(7L), "ERROR", "Tipo nao suportado", false));

        assertEquals(1, response.get("deadLettered"));
        assertEquals(0, response.get("retryScheduled"));
        assertEquals(1, jdbcTemplate.deadUpdates);
    }

    @Test
    void manualRetryReturnsItemToPendingWithoutTouchingAckedRows() {
        Map<String, Object> response = service.retryOutboxItem("tenant-1", "store-1", 7L, "retry manual");

        assertEquals("RETRY_SCHEDULED", response.get("status"));
        assertEquals(1, jdbcTemplate.retryUpdates);
    }

    @Test
    void manualDeadLetterIsAvailableForBadRowsWithoutBlockingQueue() {
        Map<String, Object> response = service.deadLetterOutboxItem("tenant-1", "store-1", 8L, "tipo nao suportado");

        assertEquals("DEAD_LETTERED", response.get("status"));
        assertEquals(1, jdbcTemplate.deadUpdates);
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        List<Map<String, Object>> pullRows = List.of();
        List<Map<String, Object>> ackRows = List.of();
        int deliveredUpdates;
        int deadUpdates;
        int retryUpdates;

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("SELECT id, error_count")) {
                return ackRows;
            }
            if (sql.contains("FROM web_change_outbox")) {
                return pullRows;
            }
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("SET status = 'DELIVERED'")) {
                deliveredUpdates++;
            }
            if (sql.contains("SET status = 'PENDING'")) {
                retryUpdates++;
            }
            if (sql.contains("dead_letter_at")) {
                deadUpdates++;
            }
            return 1;
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
