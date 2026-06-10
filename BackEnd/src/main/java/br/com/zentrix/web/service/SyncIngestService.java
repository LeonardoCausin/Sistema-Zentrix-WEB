package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.SyncPushRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
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
    private static final List<TableSpec> TABLES = List.of(
            table("users", List.of("username", "password", "display_name", "role", "active"), List.of("username")),
            table("suppliers", List.of("id", "name", "cnpj", "phone", "email", "address", "created_at"), List.of("id"), List.of("created_at")),
            table("clients", List.of("id", "name", "cpf_cnpj", "phone", "email", "address", "created_at"), List.of("id"), List.of("created_at")),
            table("products", List.of("code", "description", "unit", "price", "stock", "supplier_id", "min_stock"), List.of("code")),
            table("stock_movements", List.of("id", "product_code", "type", "quantity", "reason", "user", "created_at"), List.of("id"), List.of("created_at")),
            table("cash_sessions", List.of("id", "cash_id", "operator", "opening_balance", "observation", "opened_at", "closed_at", "is_open"), List.of("id"), List.of("opened_at", "closed_at")),
            table("cash_movements", List.of("id", "session_id", "type", "value", "observation", "date_time"), List.of("id"), List.of("date_time")),
            table("sales", List.of("id", "session_id", "operator", "discount", "surcharge", "payment_method", "amount_paid", "status", "date_time"), List.of("id"), List.of("date_time")),
            table("sale_items", List.of("id", "sale_id", "product_code", "quantity", "unit_price", "discount"), List.of("id")),
            table("sale_cancellations", List.of("id", "sale_id", "reason", "cancelled_by", "cancelled_at"), List.of("id"), List.of("cancelled_at")),
            table("comandas", List.of("id", "nome_cliente", "client_id", "aberta", "data_abertura", "data_fechamento"), List.of("id"), List.of("data_abertura", "data_fechamento")),
            table("comanda_itens", List.of("id", "comanda_id", "descricao", "valor", "is_produto", "product_code", "quantidade"), List.of("id")),
            table("audit_log", List.of("id", "usuario", "acao", "entity_type", "entity_id", "details", "created_at"), List.of("id"), List.of("created_at"))
    );
    private static final Map<String, TableSpec> TABLES_BY_NAME = TABLES.stream()
            .collect(Collectors.toUnmodifiableMap(TableSpec::name, spec -> spec));

    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;
    private final WebDatabaseInitializer initializer;
    private final ObjectMapper objectMapper;

    public SyncIngestService(
            JdbcTemplate jdbcTemplate,
            TransactionTemplate transactionTemplate,
            WebDatabaseInitializer initializer,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
        this.initializer = initializer;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> ingest(SyncPushRequest request) {
        validateRequest(request);
        initializer.ensureReady();

        OffsetDateTime receivedAt = OffsetDateTime.now();
        String mode = request.normalizedMode();
        Map<String, List<Map<String, Object>>> normalizedTables = normalizeTables(request.tables());

        return transactionTemplate.execute(status -> {
            Map<String, Integer> counts = new LinkedHashMap<>();
            if ("FULL".equals(mode)) {
                clearTables(normalizedTables.keySet());
            }

            for (Map.Entry<String, List<Map<String, Object>>> entry : normalizedTables.entrySet()) {
                TableSpec spec = TABLES_BY_NAME.get(entry.getKey());
                counts.put(spec.name(), upsertRows(spec, entry.getValue()));
            }

            int totalRows = counts.values().stream().mapToInt(Integer::intValue).sum();
            Long runId = recordSyncRun(request, mode, receivedAt, OffsetDateTime.now(), "SUCCESS", totalRows, counts, "Recebido via API");
            return syncResponse(runId, request.sourceId(), mode, "SUCCESS", totalRows, counts, null);
        });
    }

    public Map<String, Object> lastStatus() {
        initializer.ensureReady();
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, source_id, mode, status, generated_at, received_at, finished_at,
                       total_rows, table_counts_json, message
                FROM sync_runs
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """);
        if (rows.isEmpty()) {
            return Map.of("status", "WAITING", "message", "Nenhuma sincronizacao recebida ainda");
        }
        Map<String, Object> row = rows.get(0);
        return Map.of(
                "id", row.get("id"),
                "sourceId", row.get("source_id"),
                "mode", row.get("mode"),
                "status", row.get("status"),
                "generatedAt", row.get("generated_at"),
                "receivedAt", row.get("received_at"),
                "finishedAt", row.get("finished_at"),
                "totalRows", row.get("total_rows"),
                "tableCounts", parseCounts(row.get("table_counts_json")),
                "message", Objects.toString(row.get("message"), "")
        );
    }

    private void validateRequest(SyncPushRequest request) {
        if (request == null || request.sourceId() == null || request.sourceId().isBlank()) {
            throw new IllegalArgumentException("sourceId e obrigatorio");
        }
        if (request.tables() == null || request.tables().isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos uma tabela em tables");
        }
        String mode = request.normalizedMode();
        if (!"FULL".equals(mode) && !"PARTIAL".equals(mode)) {
            throw new IllegalArgumentException("mode deve ser FULL ou PARTIAL");
        }
    }

    private Map<String, List<Map<String, Object>>> normalizeTables(Map<String, List<Map<String, Object>>> requestedTables) {
        Map<String, List<Map<String, Object>>> normalized = new LinkedHashMap<>();
        List<String> names = new ArrayList<>(requestedTables.keySet());
        names.sort(Comparator.comparingInt(this::tableOrder));

        for (String tableName : names) {
            TableSpec spec = TABLES_BY_NAME.get(tableName);
            if (spec == null) {
                throw new IllegalArgumentException("Tabela nao permitida para sincronizacao: " + tableName);
            }
            List<Map<String, Object>> rows = requestedTables.get(tableName);
            if (rows == null) {
                rows = List.of();
            }
            for (Map<String, Object> row : rows) {
                validateRow(spec, row);
            }
            normalized.put(tableName, rows);
        }
        return normalized;
    }

    private void validateRow(TableSpec spec, Map<String, Object> row) {
        if (row == null || row.isEmpty()) {
            throw new IllegalArgumentException("Registro vazio em " + spec.name());
        }
        for (String key : row.keySet()) {
            if (!spec.columns().contains(key)) {
                throw new IllegalArgumentException("Coluna nao permitida em " + spec.name() + ": " + key);
            }
        }
        for (String key : spec.primaryKeys()) {
            if (!row.containsKey(key) || row.get(key) == null || Objects.toString(row.get(key), "").isBlank()) {
                throw new IllegalArgumentException("Chave primaria ausente em " + spec.name() + ": " + key);
            }
        }
    }

    private void clearTables(Set<String> tableNames) {
        List<String> ordered = new ArrayList<>(tableNames);
        ordered.sort((left, right) -> Integer.compare(tableOrder(right), tableOrder(left)));
        for (String tableName : ordered) {
            jdbcTemplate.update("DELETE FROM " + quote(tableName));
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
                throw new IllegalArgumentException("Data/hora invalida para sincronizacao: " + text);
            }
        }
    }

    private Long recordSyncRun(
            SyncPushRequest request,
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
                    (source_id, mode, status, generated_at, received_at, finished_at, total_rows, table_counts_json, message)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                request.sourceId(),
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
            String sourceId,
            String mode,
            String status,
            int totalRows,
            Map<String, Integer> counts,
            String message
    ) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", runId);
        response.put("sourceId", sourceId);
        response.put("mode", mode);
        response.put("status", status);
        response.put("totalRows", totalRows);
        response.put("tableCounts", counts);
        if (message != null) {
            response.put("message", message);
        }
        return response;
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

    private static TableSpec table(String name, List<String> columns, List<String> primaryKeys) {
        return table(name, columns, primaryKeys, List.of());
    }

    private static TableSpec table(String name, List<String> columns, List<String> primaryKeys, List<String> dateColumns) {
        return new TableSpec(name, Set.copyOf(columns), Set.copyOf(primaryKeys), Set.copyOf(dateColumns));
    }

    private record TableSpec(String name, Set<String> columns, Set<String> primaryKeys, Set<String> dateColumns) {
    }
}
