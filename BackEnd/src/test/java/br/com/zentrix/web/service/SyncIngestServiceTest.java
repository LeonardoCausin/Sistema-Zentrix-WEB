package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.zentrix.web.dto.SyncPushRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class SyncIngestServiceTest {
    private FakeJdbcTemplate jdbcTemplate;
    private SyncIngestService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new FakeJdbcTemplate();
        service = new SyncIngestService(
                jdbcTemplate,
                new ImmediateTransactionTemplate(),
                new NoopInitializer(),
                new ObjectMapper(),
                new NoopAuditService(),
                new PanelCacheService()
        );
    }

    @Test
    void invalidFullSyncDoesNotClearTables() {
        SyncPushRequest request = new SyncPushRequest(
                "tenant-1", "Tenant", "store-1", "Loja", "device-1", "PDV 1", "pdv-1",
                "FULL", OffsetDateTime.now(),
                Map.of("products", List.of(Map.of(
                        "code", "P1",
                        "description", "Produto",
                        "price", "10.00",
                        "updated_at", "2026-07-02T10:00:00"
                )))
        );

        assertThrows(IllegalArgumentException.class, () -> service.ingest(request));

        assertEquals(0, jdbcTemplate.deleteCalls);
    }

    @Test
    void detectsConflictAndDoesNotOverwriteNewerLocalRecord() {
        jdbcTemplate.existingRevision = List.of(Map.of(
                "source_id", "WEB",
                "updated_at", java.sql.Timestamp.valueOf("2026-07-02 11:00:00")
        ));
        SyncPushRequest request = new SyncPushRequest(
                "tenant-1", "Tenant", "store-1", "Loja", "device-1", "PDV 1", "pdv-1",
                "PARTIAL", OffsetDateTime.now(),
                Map.of("products", List.of(Map.of(
                        "code", "P1",
                        "description", "Produto antigo",
                        "price", "10.00",
                        "updated_at", "2026-07-02T10:00:00"
                )))
        );

        Map<String, Object> response = service.ingest(request);
        @SuppressWarnings("unchecked")
        Map<String, Object> reconciliation = (Map<String, Object>) response.get("reconciliation");

        assertEquals("CONFLICT", reconciliation.get("status"));
        assertEquals(1, reconciliation.get("conflictCount"));
        assertEquals(0, jdbcTemplate.batchCalls);
    }

    @Test
    void acceptsProductCreatedAtFromPdvPayload() {
        SyncPushRequest request = new SyncPushRequest(
                "tenant-1", "Tenant", "store-1", "Loja", "device-1", "PDV 1", "pdv-1",
                "PARTIAL", OffsetDateTime.now(),
                Map.of("products", List.of(Map.of(
                        "code", "P1",
                        "description", "Produto",
                        "price", "10.00",
                        "created_at", "2026-07-02T09:00:00",
                        "updated_at", "2026-07-02T10:00:00"
                )))
        );

        service.ingest(request);

        assertEquals(1, jdbcTemplate.batchCalls);
    }

    @Test
    void preservesWebOnlyCostPriceWhenPdvPushesProduct() {
        jdbcTemplate.existingCostPrice = List.of(Map.of("cost_price", new BigDecimal("12.50")));
        SyncPushRequest request = new SyncPushRequest(
                "tenant-1", "Tenant", "store-1", "Loja", "device-1", "PDV 1", "pdv-1",
                "PARTIAL", OffsetDateTime.now(),
                Map.of("products", List.of(Map.of(
                        "code", "789123",
                        "description", "Produto",
                        "price", "10.00",
                        "cost_price", "999.99",
                        "updated_at", "2026-07-02T10:00:00"
                )))
        );

        service.ingest(request);

        assertEquals(1, jdbcTemplate.batchCalls);
        assertEquals(true, jdbcTemplate.lastBatchSql.contains("cost_price"));
    }

    @Test
    void acceptsCurrentPdvSupplierAndComandaColumns() {
        SyncPushRequest request = new SyncPushRequest(
                "tenant-1", "Tenant", "store-1", "Loja", "device-1", "PDV 1", "pdv-1",
                "PARTIAL", OffsetDateTime.now(),
                Map.of(
                        "suppliers", List.of(Map.of(
                                "id", 1,
                                "name", "Fornecedor",
                                "created_at", "2026-08-05T08:00:00",
                                "birth_date", "2026-08-05",
                                "active", true,
                                "notes", "Cadastro PDV",
                                "loyalty_points", 0,
                                "updated_at", "2026-08-05T08:10:00",
                                "deleted_at", ""
                        )),
                        "comandas", List.of(Map.of(
                                "id", 10,
                                "nome_cliente", "Mesa 10",
                                "mesa", "10",
                                "aberta", true,
                                "data_abertura", "2026-08-05T08:00:00"
                        ))
                )
        );

        service.ingest(request);

        assertEquals(2, jdbcTemplate.batchCalls);
        assertEquals(true, jdbcTemplate.batchSqls.stream().anyMatch(sql -> sql.contains("`birth_date`")));
        assertEquals(true, jdbcTemplate.batchSqls.stream().anyMatch(sql -> sql.contains("`mesa`")));
    }

    @Test
    void acceptsCurrentPdvFullPayloadWithoutWebOnlyFinancialEntries() {
        SyncPushRequest request = new SyncPushRequest(
                "tenant-1", "Tenant", "store-1", "Loja", "device-1", "PDV 1", "pdv-1",
                "FULL", OffsetDateTime.now(),
                Map.ofEntries(
                        Map.entry("users", List.of(Map.of("username", "admin", "password", "x", "display_name", "Admin"))),
                        Map.entry("suppliers", List.of()),
                        Map.entry("clients", List.of()),
                        Map.entry("products", List.of(Map.of("code", "P1", "description", "Produto", "price", "10.00"))),
                        Map.entry("stock_movements", List.of()),
                        Map.entry("cash_sessions", List.of(Map.of("id", 1, "cash_id", "001", "operator", "Admin", "opening_balance", "0.00"))),
                        Map.entry("cash_movements", List.of()),
                        Map.entry("sales", List.of()),
                        Map.entry("sale_items", List.of()),
                        Map.entry("sale_cancellations", List.of()),
                        Map.entry("comandas", List.of()),
                        Map.entry("comanda_itens", List.of()),
                        Map.entry("audit_log", List.of())
                )
        );

        service.ingest(request);

        assertEquals(3, jdbcTemplate.batchCalls);
        assertEquals(13, jdbcTemplate.deleteCalls);
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        List<Map<String, Object>> existingRevision = List.of();
        List<Map<String, Object>> existingCostPrice = List.of();
        int deleteCalls;
        int batchCalls;
        String lastBatchSql = "";
        List<String> batchSqls = new java.util.ArrayList<>();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("SELECT source_id")) {
                return existingRevision;
            }
            if (sql.contains("SELECT cost_price")) {
                return existingCostPrice;
            }
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            if (sql.contains("DELETE FROM")) {
                deleteCalls++;
            }
            return 1;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType) {
            if (requiredType == Long.class) {
                return requiredType.cast(10L);
            }
            if (requiredType == Integer.class) {
                return requiredType.cast(0);
            }
            return null;
        }

        @Override
        public int[] batchUpdate(String sql, BatchPreparedStatementSetter pss) {
            batchCalls++;
            lastBatchSql = sql;
            batchSqls.add(sql);
            return new int[pss.getBatchSize()];
        }
    }

    private static class ImmediateTransactionTemplate extends TransactionTemplate {
        @Override
        public <T> T execute(TransactionCallback<T> action) {
            TransactionStatus status = new SimpleTransactionStatus();
            return action.doInTransaction(status);
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
        NoopAuditService() {
            super(null, null);
        }

        @Override
        public void record(
                String tenantId, String storeId, String deviceId, String sourceId, String user,
                String action, String entityType, String entityId, String details, String riskLevel,
                String previousValue, String newValue, String reason, String origin, String ipAddress, String userRole
        ) {
        }
    }
}
