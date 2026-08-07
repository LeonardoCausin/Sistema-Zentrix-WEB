package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.zentrix.web.dto.SyncPushRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private NoopOutboxService outboxService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new FakeJdbcTemplate();
        outboxService = new NoopOutboxService();
        service = new SyncIngestService(
                jdbcTemplate,
                new ImmediateTransactionTemplate(),
                new NoopInitializer(),
                new ObjectMapper(),
                new NoopAuditService(),
                new PanelCacheService(),
                outboxService
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
    void syncDoesNotReactivateClientOrStoreChangedByAdmin() {
        SyncPushRequest request = new SyncPushRequest(
                "tenant-1", "Tenant", "store-1", "Loja", "device-1", "PDV 1", "pdv-1",
                "PARTIAL", OffsetDateTime.now(), Map.of("products", List.of())
        );

        service.ingest(request);

        List<String> scopeUpserts = jdbcTemplate.updateSqls.stream()
                .filter(sql -> sql.contains("INSERT INTO tenants") || sql.contains("INSERT INTO tenant_stores"))
                .toList();
        assertEquals(2, scopeUpserts.size());
        assertFalse(scopeUpserts.stream().anyMatch(sql -> sql.contains("status = 'ACTIVE'")));
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
        assertEquals(9, jdbcTemplate.deleteCalls);
        assertEquals(true, jdbcTemplate.updateSqls.stream()
                .filter(sql -> sql.startsWith("DELETE FROM"))
                .allMatch(sql -> sql.contains("device_id = ?")));
    }

    @Test
    void appliesSameMovementOnlyOnce() {
        jdbcTemplate.serverStock = new BigDecimal("10.000");
        SyncPushRequest request = stockExitRequest("device-1", 1, "10.000", "9.000");

        service.ingest(request);
        service.ingest(request);

        assertEquals(new BigDecimal("9.000"), jdbcTemplate.serverStock);
        assertEquals(1, jdbcTemplate.stockUpdateCalls);
    }

    @Test
    void consolidatesEqualLocalMovementIdsFromDifferentDevices() {
        jdbcTemplate.serverStock = new BigDecimal("10.000");

        service.ingest(stockExitRequest("device-1", 1, "10.000", "9.000"));
        service.ingest(stockExitRequest("device-2", 1, "10.000", "9.000"));

        assertEquals(new BigDecimal("8.000"), jdbcTemplate.serverStock);
        assertEquals(2, jdbcTemplate.stockUpdateCalls);
    }

    @Test
    void propagatesConsolidatedStockOnlyToOtherActiveDevices() {
        jdbcTemplate.serverStock = new BigDecimal("10.000");
        jdbcTemplate.targetDevices = List.of(Map.of("id", "device-2", "source_id", "pdv-2"));

        service.ingest(stockExitRequest("device-1", 1, "10.000", "9.000"));

        assertEquals(List.of("device-2"), outboxService.targetDeviceIds);
        assertEquals(List.of("STOCK_CONSOLIDATED"), outboxService.operations);
        assertEquals(new BigDecimal("9.000"), outboxService.productStocks.get(0));
    }

    @Test
    void doesNotApplyWebMovementReplicaReturnedByPdv() {
        jdbcTemplate.serverStock = new BigDecimal("9.000");

        service.ingest(stockExitRequest("device-1", 77, "10.000", "9.000", "APPGESTAO"));

        assertEquals(new BigDecimal("9.000"), jdbcTemplate.serverStock);
        assertEquals(0, jdbcTemplate.stockUpdateCalls);
    }

    private SyncPushRequest stockExitRequest(String deviceId, int movementId, String previous, String next) {
        return stockExitRequest(deviceId, movementId, previous, next, "PDV");
    }

    private SyncPushRequest stockExitRequest(String deviceId, int movementId, String previous, String next, String origin) {
        return new SyncPushRequest(
                "tenant-1", "Tenant", "store-1", "Loja", deviceId, deviceId, deviceId,
                "PARTIAL", OffsetDateTime.now(),
                Map.of("stock_movements", List.of(Map.of(
                        "id", movementId,
                        "product_code", "P1",
                        "type", "EXIT",
                        "quantity", "1.000",
                        "previous_stock", previous,
                        "new_stock", next,
                        "origin", origin,
                        "created_at", "2026-08-06T10:00:00"
                )))
        );
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        List<Map<String, Object>> existingRevision = List.of();
        List<Map<String, Object>> existingCostPrice = List.of();
        int deleteCalls;
        int batchCalls;
        String lastBatchSql = "";
        List<String> batchSqls = new java.util.ArrayList<>();
        List<String> updateSqls = new java.util.ArrayList<>();
        Set<String> stockEffects = new HashSet<>();
        BigDecimal serverStock;
        int stockUpdateCalls;
        List<Map<String, Object>> targetDevices = List.of();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("SELECT source_id")) {
                return existingRevision;
            }
            if (sql.contains("SELECT cost_price")) {
                if (serverStock != null) {
                    return List.of(Map.of("cost_price", BigDecimal.ZERO, "stock", serverStock));
                }
                return existingCostPrice;
            }
            if (sql.contains("FROM tenant_devices") && sql.contains("id <> ?")) {
                return targetDevices;
            }
            if (sql.contains("SELECT code, description") && serverStock != null) {
                return List.of(Map.of(
                        "code", "P1",
                        "description", "Produto",
                        "price", new BigDecimal("10.00"),
                        "cost_price", BigDecimal.ZERO,
                        "stock", serverStock
                ));
            }
            return List.of();
        }

        @Override
        public int update(String sql, Object... args) {
            updateSqls.add(sql);
            if (sql.contains("INSERT IGNORE INTO sync_stock_effects")) {
                String key = args[2] + ":" + args[3];
                return stockEffects.add(key) ? 1 : 0;
            }
            if (sql.contains("UPDATE products") && sql.contains("stock = stock +")) {
                serverStock = serverStock.add((BigDecimal) args[0]);
                stockUpdateCalls++;
                return 1;
            }
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

    private static class NoopOutboxService extends WebChangeOutboxService {
        List<String> targetDeviceIds = new java.util.ArrayList<>();
        List<String> operations = new java.util.ArrayList<>();
        List<BigDecimal> productStocks = new java.util.ArrayList<>();

        NoopOutboxService() {
            super(null, null, new ObjectMapper());
        }

        @Override
        public long enqueue(
                String tenantId, String storeId, String targetSourceId, String targetDeviceId,
                String entityType, String entityId, String operation, Map<String, Object> payload
        ) {
            targetDeviceIds.add(targetDeviceId);
            operations.add(operation);
            @SuppressWarnings("unchecked")
            Map<String, Object> record = (Map<String, Object>) payload.get("record");
            productStocks.add((BigDecimal) record.get("stock"));
            return 1L;
        }
    }
}
