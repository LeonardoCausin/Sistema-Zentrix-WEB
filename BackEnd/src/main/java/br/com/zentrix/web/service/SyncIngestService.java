package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.SyncPushRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SyncIngestService {
    private static final String LEGACY_TENANT_ID = "legacy";
    private static final String LEGACY_DEVICE_ID = "legacy-device";
    private static final List<TableSpec> TABLES = List.of(
            table("users", List.of("tenant_id", "store_id", "device_id", "source_id", "username", "password", "display_name", "role", "active", "created_at", "updated_at", "last_login_at", "permissions_json"), List.of("tenant_id", "store_id", "username"), List.of("created_at", "updated_at", "last_login_at")),
            table("suppliers", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "name", "cnpj", "phone", "email", "address", "created_at"), List.of("tenant_id", "store_id", "id"), List.of("created_at")),
            table("clients", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "name", "cpf_cnpj", "phone", "email", "address", "created_at", "birth_date", "active", "notes", "loyalty_points", "updated_at", "deleted_at"), List.of("tenant_id", "store_id", "id"), List.of("created_at", "updated_at", "deleted_at")),
            table("products", List.of("tenant_id", "store_id", "device_id", "source_id", "code", "description", "unit", "price", "cost_price", "stock", "supplier_id", "category", "barcode", "min_stock", "ideal_stock", "active", "updated_at", "deleted_at"), List.of("tenant_id", "store_id", "code"), List.of("updated_at", "deleted_at")),
            table("stock_movements", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "product_code", "type", "quantity", "previous_stock", "new_stock", "origin", "reference_type", "reference_id", "reason", "user", "created_at"), List.of("tenant_id", "store_id", "id"), List.of("created_at")),
            table("cash_sessions", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "cash_id", "operator", "opening_balance", "closing_balance", "expected_balance", "difference", "observation", "opened_at", "closed_at", "closed_by", "close_reason", "is_open", "status"), List.of("tenant_id", "store_id", "id"), List.of("opened_at", "closed_at")),
            table("cash_movements", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "session_id", "type", "value", "observation", "date_time"), List.of("tenant_id", "store_id", "id"), List.of("date_time")),
            table("sales", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "session_id", "operator", "discount", "surcharge", "payment_method", "amount_paid", "status", "date_time"), List.of("tenant_id", "store_id", "id"), List.of("date_time")),
            table("sale_items", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "sale_id", "product_code", "quantity", "unit_price", "discount"), List.of("tenant_id", "store_id", "id")),
            table("sale_cancellations", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "sale_id", "reason", "cancelled_by", "cancelled_at"), List.of("tenant_id", "store_id", "id"), List.of("cancelled_at")),
            table("comandas", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "nome_cliente", "client_id", "aberta", "data_abertura", "data_fechamento"), List.of("tenant_id", "store_id", "id"), List.of("data_abertura", "data_fechamento")),
            table("comanda_itens", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "comanda_id", "descricao", "valor", "is_produto", "product_code", "quantidade"), List.of("tenant_id", "store_id", "id")),
            table("audit_log", List.of("tenant_id", "store_id", "device_id", "source_id", "id", "usuario", "acao", "entity_type", "entity_id", "details", "created_at", "risk_level", "previous_value", "new_value", "reason", "origin", "ip_address", "user_role"), List.of("tenant_id", "store_id", "id"), List.of("created_at"))
    );
    private static final Map<String, TableSpec> TABLES_BY_NAME = TABLES.stream()
            .collect(Collectors.toUnmodifiableMap(TableSpec::name, spec -> spec));

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final WebDatabaseInitializer initializer;
    private final ObjectMapper objectMapper;
    private final AuditService auditService;

    public SyncIngestService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            WebDatabaseInitializer initializer,
            ObjectMapper objectMapper,
            AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.initializer = initializer;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
    }

    public Map<String, Object> ingest(SyncPushRequest request) {
        SyncScope scope = null;
        OffsetDateTime receivedAt = OffsetDateTime.now();
        try {
            validateRequest(request);
            initializer.ensureReady();

            scope = scope(request);
            String mode = request.normalizedMode();
            Map<String, List<Map<String, Object>>> normalizedTables = normalizeTables(scope, request.tables());
            SyncScope syncScope = scope;

            return transactionTemplate.execute(status -> {
                upsertScopeMetadata(syncScope, receivedAt);
                Map<String, Integer> counts = new LinkedHashMap<>();
                if ("FULL".equals(mode)) {
                    clearTables(syncScope, normalizedTables.keySet());
                }

                for (Map.Entry<String, List<Map<String, Object>>> entry : normalizedTables.entrySet()) {
                    TableSpec spec = TABLES_BY_NAME.get(entry.getKey());
                    counts.put(spec.name(), upsertRows(spec, entry.getValue()));
                }

                int totalRows = counts.values().stream().mapToInt(Integer::intValue).sum();
                Long runId = recordSyncRun(request, syncScope, mode, receivedAt, OffsetDateTime.now(), "SUCCESS", totalRows, counts, "Recebido via API");
                auditService.record(syncScope.tenantId(), syncScope.storeId(), syncScope.deviceId(), syncScope.sourceId(), "PDV", "SYNC_SUCCESS", "sync_runs", String.valueOf(runId), "Sincronização recebida com " + totalRows + " registro(s).", "INFO", null, countsJson(counts), null, "PDV", null, null);
                return syncResponse(runId, syncScope, mode, "SUCCESS", totalRows, counts, null);
            });
        } catch (RuntimeException e) {
            if (scope != null) {
                try {
                    Long runId = recordSyncRun(request, scope, request == null ? "PARTIAL" : request.normalizedMode(), receivedAt, OffsetDateTime.now(), "ERROR", 0, Map.of(), e.getMessage());
                    auditService.record(scope.tenantId(), scope.storeId(), scope.deviceId(), scope.sourceId(), "PDV", "SYNC_ERROR", "sync_runs", String.valueOf(runId), e.getMessage(), "CRITICO", null, null, "Falha ao sincronizar", "PDV", null, null);
                } catch (RuntimeException ignored) {
                    // Mantém o erro original da sincronização.
                }
            }
            throw e;
        }
    }

    public Map<String, Object> lastStatus() {
        return lastStatus(null, null, null);
    }

    public Map<String, Object> lastStatus(String tenantId, String storeId, String sourceId) {
        initializer.ensureReady();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tenant_id, store_id, device_id, source_id, mode, status, generated_at, received_at, finished_at,
                       total_rows, table_counts_json, message
                FROM sync_runs
                WHERE (? IS NULL OR tenant_id = ?)
                  AND (? IS NULL OR store_id = ?)
                  AND (? IS NULL OR source_id = ?)
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """, emptyToNull(tenantId), emptyToNull(tenantId), emptyToNull(storeId), emptyToNull(storeId), emptyToNull(sourceId), emptyToNull(sourceId));
        if (rows.isEmpty()) {
            return Map.of("status", "WAITING", "message", "Nenhuma sincronização recebida ainda");
        }
        Map<String, Object> row = rows.get(0);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", row.get("id"));
        response.put("tenantId", row.get("tenant_id"));
        response.put("storeId", row.get("store_id"));
        response.put("deviceId", row.get("device_id"));
        response.put("sourceId", row.get("source_id"));
        response.put("mode", row.get("mode"));
        response.put("status", row.get("status"));
        response.put("generatedAt", row.get("generated_at"));
        response.put("receivedAt", row.get("received_at"));
        response.put("finishedAt", row.get("finished_at"));
        response.put("totalRows", row.get("total_rows"));
        response.put("tableCounts", parseCounts(row.get("table_counts_json")));
        response.put("message", Objects.toString(row.get("message"), ""));
        return response;
    }

    private void validateRequest(SyncPushRequest request) {
        if (request == null || request.sourceId() == null || request.sourceId().isBlank()) {
            throw new IllegalArgumentException("sourceId é obrigatório");
        }
        if (request.tables() == null || request.tables().isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos uma tabela em tables");
        }
        String mode = request.normalizedMode();
        if (!"FULL".equals(mode) && !"PARTIAL".equals(mode)) {
            throw new IllegalArgumentException("mode deve ser FULL ou PARTIAL");
        }
    }

    private SyncScope scope(SyncPushRequest request) {
        String sourceId = requiredText(request.sourceId(), "sourceId");
        String tenantId = optionalText(request.tenantId(), LEGACY_TENANT_ID);
        String storeId = optionalText(request.storeId(), sourceId);
        String deviceId = optionalText(request.deviceId(), LEGACY_DEVICE_ID);
        String tenantName = optionalText(request.tenantName(), tenantId.equals(LEGACY_TENANT_ID) ? "Cliente legado" : tenantId);
        String storeName = optionalText(request.storeName(), displayName(sourceId));
        String deviceName = optionalText(request.deviceName(), deviceId);
        return new SyncScope(tenantId, tenantName, storeId, storeName, deviceId, deviceName, sourceId);
    }

    private void upsertScopeMetadata(SyncScope scope, OffsetDateTime receivedAt) {
        jdbcTemplate.update("""
                INSERT INTO tenants (id, name, status)
                VALUES (?, ?, 'ACTIVE')
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    status = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP
                """, scope.tenantId(), scope.tenantName());
        jdbcTemplate.update("""
                INSERT INTO tenant_stores (tenant_id, id, name, source_id, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    source_id = VALUES(source_id),
                    status = 'ACTIVE',
                    updated_at = CURRENT_TIMESTAMP
                """, scope.tenantId(), scope.storeId(), scope.storeName(), scope.sourceId());
        jdbcTemplate.update("""
                INSERT INTO tenant_devices (tenant_id, store_id, id, name, source_id, status, last_seen_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?)
                ON DUPLICATE KEY UPDATE
                    name = VALUES(name),
                    source_id = VALUES(source_id),
                    status = 'ACTIVE',
                    last_seen_at = VALUES(last_seen_at),
                    updated_at = CURRENT_TIMESTAMP
                """, scope.tenantId(), scope.storeId(), scope.deviceId(), scope.deviceName(), scope.sourceId(), Timestamp.from(receivedAt.toInstant()));
    }

    private Map<String, List<Map<String, Object>>> normalizeTables(SyncScope scope, Map<String, List<Map<String, Object>>> requestedTables) {
        Map<String, List<Map<String, Object>>> normalized = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(requestedTables.keySet());
        names.sort(Comparator.comparingInt(this::tableOrder));

        for (String tableName : names) {
            TableSpec spec = TABLES_BY_NAME.get(tableName);
            if (spec == null) {
            throw new IllegalArgumentException("Tabela não permitida para sincronização: " + tableName);
            }
            List<Map<String, Object>> rows = requestedTables.get(tableName);
            if (rows == null) {
                rows = List.of();
            }
            List<Map<String, Object>> scopedRows = rows.stream()
                    .map(row -> compatibleRow(spec, row))
                    .map(row -> scopedRow(scope, row))
                    .toList();
            for (Map<String, Object> row : scopedRows) {
                validateRow(spec, row);
            }
            normalized.put(tableName, scopedRows);
        }
        return normalized;
    }

    private Map<String, Object> compatibleRow(TableSpec spec, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> compatible = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : row.entrySet()) {
            String column = entry.getKey();
            if (spec.columns().contains(column)) {
                compatible.put(column, entry.getValue());
                continue;
            }
            if (isIgnorablePdvColumn(column)) {
                continue;
            }
            compatible.put(column, entry.getValue());
        }
        return compatible;
    }

    private boolean isIgnorablePdvColumn(String column) {
        return column != null && (column.endsWith("_uid") || column.equals("sync_status") || column.equals("last_sync_at"));
    }

    private Map<String, Object> scopedRow(SyncScope scope, Map<String, Object> row) {
        if (row == null) {
            return Map.of();
        }
        Map<String, Object> scoped = new LinkedHashMap<>(row);
        scoped.put("tenant_id", scope.tenantId());
        scoped.put("store_id", scope.storeId());
        scoped.put("device_id", scope.deviceId());
        scoped.put("source_id", scope.sourceId());
        return scoped;
    }

    private void validateRow(TableSpec spec, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException("Registro vazio em " + spec.name());
        }
        for (String key : row.keySet()) {
            if (!spec.columns().contains(key)) {
                throw new IllegalArgumentException("Coluna não permitida em " + spec.name() + ": " + key);
            }
        }
        for (String key : spec.primaryKeys()) {
            if (!row.containsKey(key) || row.get(key) == null || Objects.toString(row.get(key), "").isBlank()) {
                throw new IllegalArgumentException("Chave primária ausente em " + spec.name() + ": " + key);
            }
        }
    }

    private void clearTables(SyncScope scope, Set<String> tableNames) {
        List<String> ordered = new ArrayList<>(tableNames);
        ordered.sort((left, right) -> Integer.compare(tableOrder(right), tableOrder(left)));
        for (String tableName : ordered) {
            jdbcTemplate.update("DELETE FROM " + quote(tableName) + " WHERE tenant_id = ? AND store_id = ?",
                    scope.tenantId(), scope.storeId());
        }
    }

    private int upsertRows(TableSpec spec, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return 0;
        }

        Map<Set<String>, List<Map<String, Object>>> groups = rows.stream()
                .collect(Collectors.groupingBy(row -> new LinkedHashSet<>(row.keySet()), LinkedHashMap::new, Collectors.toList()));
        int total = 0;
        for (Map.Entry<Set<String>, List<Map<String, Object>>> group : groups.entrySet()) {
            List<String> columns = new ArrayList<>(group.getKey());
            String sql = upsertSql(spec, columns);
            List<Map<String, Object>> groupedRows = group.getValue();
            jdbcTemplate.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int i) throws SQLException {
                    Map<String, Object> row = groupedRows.get(i);
                    for (int columnIndex = 0; columnIndex < columns.size(); columnIndex++) {
                        String column = columns.get(columnIndex);
                        ps.setObject(columnIndex + 1, normalizeValue(spec, column, row.get(column)));
                    }
                }

                @Override
                public int getBatchSize() {
                    return groupedRows.size();
                }
            });
            total += groupedRows.size();
        }
        return total;
    }

    private String upsertSql(TableSpec spec, List<String> columns) {
        String columnSql = columns.stream().map(this::quote).collect(Collectors.joining(", "));
        String placeholders = columns.stream().map(column -> "?").collect(Collectors.joining(", "));
        List<String> updateColumns = columns.stream()
                .filter(column -> !spec.primaryKeys().contains(column))
                .toList();
        String updateSql = updateColumns.isEmpty()
                ? spec.primaryKeys().stream().map(column -> quote(column) + " = " + quote(column)).collect(Collectors.joining(", "))
                : updateColumns.stream().map(column -> quote(column) + " = VALUES(" + quote(column) + ")").collect(Collectors.joining(", "));
        return "INSERT INTO " + quote(spec.name()) + " (" + columnSql + ") VALUES (" + placeholders
                + ") ON DUPLICATE KEY UPDATE " + updateSql;
    }

    private Object normalizeValue(TableSpec spec, String column, Object value) {
        if (value == null) {
            return null;
        }
        if (spec.dateColumns().contains(column) && value instanceof String text) {
            return parseDateTime(text);
        }
        return value;
    }

    private String normalizeText(Object value) {
        String text = Objects.toString(value, "").trim().toUpperCase();
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private Timestamp parseDateTime(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String value = text.trim();
        try {
            return Timestamp.from(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException ignored) {
            // Tenta os formatos sem offset usados em DATETIME.
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(value));
        } catch (DateTimeParseException ignored) {
            // Tenta o formato aceito pelo MySQL.
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(value.replace(' ', 'T')));
        } catch (DateTimeParseException ignored) {
            // Tenta data simples.
        }
        try {
            return Timestamp.valueOf(LocalDate.parse(value).atStartOfDay());
        } catch (DateTimeParseException ignored) {
            try {
                return Timestamp.valueOf(value.replace('T', ' ').replaceAll("Z$", ""));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Data/hora inválida para sincronização: " + text);
            }
        }
    }

    private Long recordSyncRun(
            SyncPushRequest request,
            SyncScope scope,
            String mode,
            OffsetDateTime receivedAt,
            OffsetDateTime finishedAt,
            String status,
            int totalRows,
            Map<String, Integer> counts,
            String message
    ) {
        jdbcTemplate.update("""
                INSERT INTO sync_runs
                    (tenant_id, store_id, device_id, source_id, mode, status, generated_at, received_at, finished_at, total_rows, table_counts_json, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                scope.tenantId(),
                scope.storeId(),
                scope.deviceId(),
                scope.sourceId(),
                mode,
                status,
                request.generatedAt() == null ? null : Timestamp.from(request.generatedAt().toInstant()),
                Timestamp.from(receivedAt.toInstant()),
                Timestamp.from(finishedAt.toInstant()),
                totalRows,
                countsJson(counts),
                message
        );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private String countsJson(Map<String, Integer> counts) {
        try {
            return objectMapper.writeValueAsString(counts);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private Map<String, Integer> parseCounts(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(Objects.toString(value), objectMapper.getTypeFactory()
                    .constructMapType(LinkedHashMap.class, String.class, Integer.class));
        } catch (Exception e) {
            return Map.of();
        }
    }

    private Map<String, Object> syncResponse(
            Long runId,
            SyncScope scope,
            String mode,
            String status,
            int totalRows,
            Map<String, Integer> counts,
            String message
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", runId);
        response.put("tenantId", scope.tenantId());
        response.put("storeId", scope.storeId());
        response.put("deviceId", scope.deviceId());
        response.put("sourceId", scope.sourceId());
        response.put("mode", mode);
        response.put("status", status);
        response.put("totalRows", totalRows);
        response.put("tableCounts", counts);
        if (message != null) {
            response.put("message", message);
        }
        return response;
    }

    private String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " é obrigatório");
        }
        return value.trim();
    }

    private String optionalText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String displayName(String value) {
        String normalized = value.trim()
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .toLowerCase();
        StringBuilder builder = new StringBuilder();
        for (String part : normalized.split(" ")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase()).append(part.substring(1));
        }
        return builder.isEmpty() ? value : builder.toString();
    }

    private int tableOrder(String tableName) {
        for (int i = 0; i < TABLES.size(); i++) {
            if (TABLES.get(i).name().equals(tableName)) {
                return i;
            }
        }
        return Integer.MAX_VALUE;
    }

    private String quote(String identifier) {
        return "`" + identifier + "`";
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static TableSpec table(String name, List<String> columns, List<String> primaryKeys) {
        return table(name, columns, primaryKeys, List.of());
    }

    private static TableSpec table(String name, List<String> columns, List<String> primaryKeys, List<String> dateColumns) {
        return new TableSpec(name, Set.copyOf(columns), Set.copyOf(primaryKeys), Set.copyOf(dateColumns));
    }

    private record SyncScope(
            String tenantId,
            String tenantName,
            String storeId,
            String storeName,
            String deviceId,
            String deviceName,
            String sourceId
    ) {
    }

    private record TableSpec(String name, Set<String> columns, Set<String> primaryKeys, Set<String> dateColumns) {
    }
}
