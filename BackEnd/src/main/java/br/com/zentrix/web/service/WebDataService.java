package br.com.zentrix.web.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebDataService {
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;

    public WebDataService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
    }

    public Map<String, Object> dashboard() {
        initializer.ensureReady();
        BigDecimal todayTotal = money("""
                SELECT COALESCE(SUM(total), 0)
                FROM (
                    SELECT s.id,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                    FROM sales s
                    LEFT JOIN sale_items si ON si.sale_id = s.id
                    WHERE s.status = 'PAID' AND DATE(s.date_time) = CURDATE()
                    GROUP BY s.id, s.discount, s.surcharge
                ) totals
                """);
        BigDecimal monthTotal = money("""
                SELECT COALESCE(SUM(total), 0)
                FROM (
                    SELECT s.id,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                    FROM sales s
                    LEFT JOIN sale_items si ON si.sale_id = s.id
                    WHERE s.status = 'PAID' AND s.date_time >= DATE_FORMAT(CURDATE(), '%Y-%m-01')
                    GROUP BY s.id, s.discount, s.surcharge
                ) totals
                """);
        Long paidSales = number("SELECT COUNT(*) FROM sales WHERE status = 'PAID' AND DATE(date_time) = CURDATE()");
        BigDecimal averageTicket = paidSales == 0 ? BigDecimal.ZERO : todayTotal.divide(BigDecimal.valueOf(paidSales), 2, RoundingMode.HALF_UP);
        Long lowStock = number("SELECT COUNT(*) FROM products WHERE stock <= min_stock");
        Long criticalStock = number("SELECT COUNT(*) FROM products WHERE stock <= 0");

        return Map.of(
                "company", Map.of("id", "WEB-001", "name", "Zentrix Web"),
                "lastSync", lastSync(),
                "syncProgress", lastSyncProgress(),
                "metrics", List.of(
                        metric("Faturamento hoje", currency(todayTotal), "", "success"),
                        metric("Faturamento do mes", currency(monthTotal), "", "success"),
                        metric("Ticket medio", currency(averageTicket), "", "warning"),
                        metric("Estoque baixo", String.valueOf(lowStock), criticalStock + " criticos", "danger")
                ),
                "payments", payments(todayTotal)
        );
    }

    public List<Map<String, Object>> sales() {
        initializer.ensureReady();
        return jdbcTemplate.query("""
                SELECT s.id, s.operator, s.payment_method, s.status, s.date_time,
                       COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                FROM sales s
                LEFT JOIN sale_items si ON si.sale_id = s.id
                GROUP BY s.id, s.operator, s.payment_method, s.status, s.date_time, s.discount, s.surcharge
                ORDER BY s.date_time DESC, s.id DESC
                LIMIT 50
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", "ZV-" + rs.getInt("id"));
            row.put("time", rs.getTimestamp("date_time") == null ? "-" : rs.getTimestamp("date_time").toLocalDateTime().toLocalTime().toString());
            row.put("operator", rs.getString("operator"));
            row.put("client", "Consumidor final");
            row.put("payment", paymentName(rs.getString("payment_method")));
            row.put("status", statusName(rs.getString("status")));
            row.put("total", currency(rs.getBigDecimal("total")));
            return row;
        });
    }

    public List<Map<String, Object>> products() {
        initializer.ensureReady();
        return jdbcTemplate.query("""
                SELECT code, description, unit, price, stock, min_stock
                FROM products
                ORDER BY description
                LIMIT 100
                """, (rs, rowNum) -> productRow(
                rs.getString("description"),
                rs.getString("code"),
                rs.getString("unit"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("stock"),
                rs.getBigDecimal("min_stock")
        ));
    }

    public List<Map<String, Object>> cashSessions() {
        initializer.ensureReady();
        return jdbcTemplate.query("""
                SELECT id, cash_id, operator, opening_balance, opened_at, closed_at, is_open
                FROM cash_sessions
                ORDER BY opened_at DESC, id DESC
                LIMIT 50
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", rs.getString("cash_id") == null ? "CX-" + rs.getInt("id") : rs.getString("cash_id"));
            row.put("operator", rs.getString("operator"));
            row.put("openedAt", rs.getTimestamp("opened_at") == null ? "-" : rs.getTimestamp("opened_at").toLocalDateTime().toLocalTime().toString());
            row.put("closedAt", rs.getTimestamp("closed_at") == null ? "Aberto" : rs.getTimestamp("closed_at").toLocalDateTime().toLocalTime().toString());
            row.put("status", rs.getBoolean("is_open") ? "Aberto" : "Fechado");
            row.put("expected", currency(rs.getBigDecimal("opening_balance")));
            row.put("informed", "-");
            row.put("difference", "-");
            return row;
        });
    }

    public List<Map<String, Object>> stockAlerts() {
        initializer.ensureReady();
        return jdbcTemplate.query("""
                SELECT code, description, unit, price, stock, min_stock
                FROM products
                WHERE stock <= min_stock
                ORDER BY stock ASC, description
                LIMIT 100
                """, (rs, rowNum) -> productRow(
                rs.getString("description"),
                rs.getString("code"),
                rs.getString("unit"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("stock"),
                rs.getBigDecimal("min_stock")
        ));
    }

    public List<Map<String, Object>> auditEvents() {
        initializer.ensureReady();
        return jdbcTemplate.query("""
                SELECT acao, usuario, entity_type, entity_id, details, created_at
                FROM audit_log
                ORDER BY created_at DESC, id DESC
                LIMIT 50
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("action", rs.getString("acao"));
            row.put("time", rs.getTimestamp("created_at") == null ? "-" : rs.getTimestamp("created_at").toLocalDateTime().toLocalTime().toString());
            row.put("user", rs.getString("usuario"));
            row.put("description", rs.getString("entity_type") + " " + rs.getString("entity_id"));
            row.put("value", rs.getString("details"));
            return row;
        });
    }

    public List<Map<String, Object>> backups() {
        initializer.ensureReady();
        return jdbcTemplate.query("""
                SELECT received_at, source_id, total_rows, status
                FROM sync_runs
                ORDER BY received_at DESC, id DESC
                LIMIT 20
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("date", rs.getTimestamp("received_at") == null ? "-" : rs.getTimestamp("received_at").toString());
            row.put("origin", rs.getString("source_id"));
            row.put("size", rs.getInt("total_rows") + " registros");
            row.put("status", rs.getString("status"));
            return row;
        });
    }

    private List<Map<String, Object>> payments(BigDecimal todayTotal) {
        return jdbcTemplate.query("""
                SELECT s.payment_method,
                       COALESCE(SUM(total), 0) AS total
                FROM (
                    SELECT s.id, s.payment_method,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                    FROM sales s
                    LEFT JOIN sale_items si ON si.sale_id = s.id
                    WHERE s.status = 'PAID' AND DATE(s.date_time) = CURDATE()
                    GROUP BY s.id, s.payment_method, s.discount, s.surcharge
                ) s
                GROUP BY s.payment_method
                ORDER BY total DESC
                """, (rs, rowNum) -> {
            BigDecimal total = rs.getBigDecimal("total") == null ? BigDecimal.ZERO : rs.getBigDecimal("total");
            int percent = todayTotal.compareTo(BigDecimal.ZERO) == 0
                    ? 0
                    : total.multiply(BigDecimal.valueOf(100)).divide(todayTotal, 0, RoundingMode.HALF_UP).intValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", paymentName(rs.getString("payment_method")));
            row.put("percent", percent);
            row.put("total", currency(total));
            return row;
        });
    }

    private Map<String, Object> productRow(String name, String code, String unit, BigDecimal price, BigDecimal stock, BigDecimal minStock) {
        BigDecimal safeStock = stock == null ? BigDecimal.ZERO : stock;
        BigDecimal safeMinStock = minStock == null ? BigDecimal.ZERO : minStock;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("name", name);
        row.put("code", code);
        row.put("category", unit == null ? "UN" : unit);
        row.put("price", currency(price));
        row.put("currentStock", safeStock.setScale(0, RoundingMode.DOWN).intValue());
        row.put("minimumStock", safeMinStock.setScale(0, RoundingMode.DOWN).intValue());
        row.put("status", safeStock.compareTo(BigDecimal.ZERO) <= 0 ? "Sem estoque" : safeStock.compareTo(safeMinStock) <= 0 ? "Estoque baixo" : "Ativo");
        return row;
    }

    private Map<String, Object> metric(String label, String value, String trend, String tone) {
        return Map.of("label", label, "value", value, "trend", trend, "tone", tone);
    }

    private BigDecimal money(String sql) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private Long number(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private String lastSync() {
        List<String> result = jdbcTemplate.query("""
                SELECT CAST(received_at AS CHAR)
                FROM sync_runs
                WHERE status = 'SUCCESS'
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getString(1));
        return result.isEmpty() ? null : result.get(0);
    }

    private int lastSyncProgress() {
        Long success = number("SELECT COUNT(*) FROM sync_runs WHERE status = 'SUCCESS'");
        return success > 0 ? 100 : 0;
    }

    private String currency(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(PT_BR).format(value == null ? BigDecimal.ZERO : value);
    }

    private String paymentName(String value) {
        if (value == null) {
            return "Nao informado";
        }
        return switch (value.toUpperCase()) {
            case "CASH" -> "Dinheiro";
            case "CARD" -> "Cartao";
            case "PIX" -> "Pix";
            case "OTHER" -> "Outro";
            default -> value;
        };
    }

    private String statusName(String value) {
        if (value == null) {
            return "Aberta";
        }
        return switch (value.toUpperCase()) {
            case "PAID" -> "Concluida";
            case "CANCELLED" -> "Cancelada";
            case "OPEN" -> "Aberta";
            default -> value;
        };
    }
}
