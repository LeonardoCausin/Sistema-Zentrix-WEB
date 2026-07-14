package br.com.zentrix.web.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReportService {
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final Duration REPORT_CACHE_TTL = Duration.ofSeconds(20);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final WebDataService webDataService;
    private final PanelCacheService panelCacheService;

    public ReportService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer, WebDataService webDataService, PanelCacheService panelCacheService) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.webDataService = webDataService;
        this.panelCacheService = panelCacheService;
    }

    public Map<String, Object> overview(String tenantId, String period, String store) {
        return panelCacheService.get(panelCacheService.key("report-overview", tenantId, normalizePeriod(period), normalizeStore(store)),
                REPORT_CACHE_TTL, () -> overviewUncached(tenantId, period, store));
    }

    private Map<String, Object> overviewUncached(String tenantId, String period, String store) {
        initializer.ensureReady();
        Filter salesFilter = salesFilter(tenantId, period, store);
        Filter productFilter = scopeFilter(tenantId, store, "p");
        Filter clientFilter = scopeFilter(tenantId, store, "c");
        Filter cashFilter = cashFilter(tenantId, period, store);
        Filter auditFilter = auditFilter(tenantId, period, store);
        long salesCount = number("SELECT COUNT(*) FROM sales s WHERE " + salesFilter.sql(), salesFilter.args());
        long productCount = number("SELECT COUNT(*) FROM products p WHERE " + productFilter.sql(), productFilter.args());
        long clientCount = number("SELECT COUNT(*) FROM clients c WHERE " + clientFilter.sql(), clientFilter.args());
        long cashCount = number("SELECT COUNT(*) FROM cash_sessions cs WHERE " + cashFilter.sql(), cashFilter.args());
        long stockAlerts = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.stock <= p.min_stock AND " + productFilter.sql(), productFilter.args());
        long auditEvents = number("SELECT COUNT(*) FROM audit_log a WHERE " + auditFilter.sql(), auditFilter.args());
        BigDecimal revenue = salesTotal(salesFilter);

        Map<String, Object> response = baseReport("Central de Relat\u00f3rios", tenantId, period, store);
        response.put("sales", salesCount);
        response.put("products", productCount);
        response.put("clients", clientCount);
        response.put("cashSessions", cashCount);
        response.put("stockAlerts", stockAlerts);
        response.put("auditEvents", auditEvents);
        response.put("lastSync", lastSync(tenantId, store));
        response.put("revenueChart", revenueChart(tenantId, period, store));
        response.put("salesByStore", salesByStore(tenantId, period, store));
        response.put("stockHealth", stockHealth(tenantId, store));
        response.put("topProducts", topProducts(tenantId, period, store));
        response.put("availableReports", reportCards());
        response.put("reportCards", reportCards());
        response.put("summaryCards", List.of(
                metric("Faturamento", currency(revenue), "Receita confirmada no per\u00edodo", "info"),
                metric("Vendas", String.valueOf(salesCount), "Registros sincronizados do PDV", "success"),
                metric("Caixas", String.valueOf(cashCount), cashCount == 0 ? "Nenhuma sess\u00e3o recebida no per\u00edodo" : "Sess\u00f5es operacionais", cashCount == 0 ? "warning" : "info"),
                metric("Estoque cr\u00edtico", String.valueOf(stockAlerts), "Produtos abaixo do m\u00ednimo", stockAlerts > 0 ? "warning" : "success")
        ));
        response.put("salesReport", sales(tenantId, period, store));
        response.put("cashReport", cash(tenantId, period, store));
        response.put("financeReport", finance(tenantId, period, store));
        response.put("diagnostics", diagnostics(tenantId, period, store));
        return response;
    }

    public Map<String, Object> sales(String tenantId, String period, String store) {
        return panelCacheService.get(panelCacheService.key("report-sales", tenantId, normalizePeriod(period), normalizeStore(store)),
                REPORT_CACHE_TTL, () -> salesUncached(tenantId, period, store));
    }

    private Map<String, Object> salesUncached(String tenantId, String period, String store) {
        initializer.ensureReady();
        Filter filter = salesFilter(tenantId, period, store);
        long totalSales = number("SELECT COUNT(*) FROM sales s WHERE " + filter.sql(), filter.args());
        long paidSales = number("SELECT COUNT(*) FROM sales s WHERE s.status = 'PAID' AND " + filter.sql(), filter.args());
        long cancelledSales = number("SELECT COUNT(*) FROM sales s WHERE s.status = 'CANCELLED' AND " + filter.sql(), filter.args());
        BigDecimal revenue = salesTotal(filter);
        BigDecimal profit = estimatedProfit(filter);

        Map<String, Object> response = baseReport("Relat\u00f3rio de Vendas", tenantId, period, store);
        response.put("summaryCards", List.of(
                metric("Vendas", String.valueOf(totalSales), "Total de cupons no per\u00edodo", "info"),
                metric("Faturamento", currency(revenue), "Vendas pagas", "success"),
                metric("Ticket m\u00e9dio", currency(paidSales == 0 ? BigDecimal.ZERO : revenue.divide(BigDecimal.valueOf(paidSales), 2, RoundingMode.HALF_UP)), "M\u00e9dia por venda paga", "info"),
                metric("Canceladas", String.valueOf(cancelledSales), "Vendas canceladas", cancelledSales > 0 ? "warning" : "success")
        ));
        response.put("payments", payments(filter));
        response.put("topProducts", topProducts(tenantId, period, store));
        response.put("revenueChart", revenueChart(tenantId, period, store));
        response.put("rows", webDataService.sales(tenantId, period, store));
        response.put("insights", List.of(
                "Faturamento estimado: " + currency(revenue),
                "Lucro estimado: " + currency(profit),
                cancelledSales > 0 ? "Existem cancelamentos que devem ser auditados." : "Nenhum cancelamento relevante no per\u00edodo."
        ));
        return response;
    }

    public Map<String, Object> products(String tenantId, String store) {
        initializer.ensureReady();
        Filter filter = scopeFilter(tenantId, store, "p");
        long total = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND " + filter.sql(), filter.args());
        long active = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.active = TRUE AND " + filter.sql(), filter.args());
        long inactive = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.active = FALSE AND " + filter.sql(), filter.args());
        long noStock = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.stock <= 0 AND " + filter.sql(), filter.args());
        Map<String, Object> response = baseReport("Relat\u00f3rio de Produtos", tenantId, "today", store);
        response.put("summaryCards", List.of(
                metric("Produtos", String.valueOf(total), "Itens cadastrados", "info"),
                metric("Ativos", String.valueOf(active), "Dispon\u00edveis para venda", "success"),
                metric("Inativos", String.valueOf(inactive), "Bloqueados ou descontinuados", inactive > 0 ? "warning" : "success"),
                metric("Sem estoque", String.valueOf(noStock), "Risco de ruptura", noStock > 0 ? "danger" : "success")
        ));
        response.put("categories", rows("""
                SELECT COALESCE(NULLIF(p.category, ''), 'Sem categoria') AS label, COUNT(*) AS value
                FROM products p
                WHERE p.deleted_at IS NULL AND %s
                GROUP BY COALESCE(NULLIF(p.category, ''), 'Sem categoria')
                ORDER BY value DESC, label
                LIMIT 10
                """.formatted(filter.sql()), filter.args()));
        response.put("rows", webDataService.products(tenantId, store));
        return response;
    }

    public Map<String, Object> stock(String tenantId, String store) {
        initializer.ensureReady();
        Filter filter = scopeFilter(tenantId, store, "p");
        long low = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.stock > 0 AND p.stock <= p.min_stock AND " + filter.sql(), filter.args());
        long empty = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.stock <= 0 AND " + filter.sql(), filter.args());
        long healthy = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.stock > p.min_stock AND " + filter.sql(), filter.args());
        Map<String, Object> response = baseReport("Relat\u00f3rio de Estoque", tenantId, "today", store);
        response.put("summaryCards", List.of(
                metric("Saud\u00e1veis", String.valueOf(healthy), "Produtos acima do m\u00ednimo", "success"),
                metric("Baixo estoque", String.valueOf(low), "Precisam de reposi\u00e7\u00e3o", low > 0 ? "warning" : "success"),
                metric("Zerados", String.valueOf(empty), "Ruptura imediata", empty > 0 ? "danger" : "success")
        ));
        response.put("alerts", webDataService.stockAlerts(tenantId, store));
        response.put("stockHealth", stockHealth(tenantId, store));
        response.put("movements", stockMovements(tenantId, store));
        return response;
    }

    public Map<String, Object> cash(String tenantId, String period, String store) {
        return panelCacheService.get(panelCacheService.key("report-cash", tenantId, normalizePeriod(period), normalizeStore(store)),
                REPORT_CACHE_TTL, () -> cashUncached(tenantId, period, store));
    }

    private Map<String, Object> cashUncached(String tenantId, String period, String store) {
        initializer.ensureReady();
        Filter filter = cashFilter(tenantId, period, store);
        Filter movementFilter = cashMovementFilter(tenantId, period, store);
        long sessions = number("SELECT COUNT(*) FROM cash_sessions cs WHERE " + filter.sql(), filter.args());
        long open = number("SELECT COUNT(*) FROM cash_sessions cs WHERE cs.closed_at IS NULL AND (cs.is_open = TRUE OR UPPER(COALESCE(cs.status, '')) IN ('OPEN', 'ABERTO')) AND " + filter.sql(), filter.args());
        long closed = number("SELECT COUNT(*) FROM cash_sessions cs WHERE (cs.closed_at IS NOT NULL OR cs.is_open = FALSE OR UPPER(COALESCE(cs.status, '')) IN ('CLOSED', 'FECHADO')) AND " + filter.sql(), filter.args());
        long withoutOpenDate = number("SELECT COUNT(*) FROM cash_sessions cs WHERE cs.opened_at IS NULL AND " + filter.sql(), filter.args());
        long movements = number("SELECT COUNT(*) FROM cash_movements cm WHERE " + movementFilter.sql(), movementFilter.args());
        BigDecimal openingTotal = money("SELECT COALESCE(SUM(cs.opening_balance), 0) FROM cash_sessions cs WHERE " + filter.sql(), filter.args());
        BigDecimal closingTotal = money("SELECT COALESCE(SUM(cs.closing_balance), 0) FROM cash_sessions cs WHERE " + filter.sql(), filter.args());
        BigDecimal differenceTotal = money("SELECT COALESCE(SUM(cs.difference), 0) FROM cash_sessions cs WHERE " + filter.sql(), filter.args());

        Map<String, Object> response = baseReport("Relat\u00f3rio de Caixa", tenantId, period, store);
        response.put("summaryCards", List.of(
                metric("Sess\u00f5es", String.valueOf(sessions), "Caixas no per\u00edodo", sessions == 0 ? "warning" : "info"),
                metric("Abertos", String.valueOf(open), "Caixas operando", open > 0 ? "success" : "info"),
                metric("Fechados", String.valueOf(closed), "Caixas encerrados", "info"),
                metric("Diferen\u00e7a", currency(differenceTotal), "Diverg\u00eancia informada", differenceTotal.compareTo(BigDecimal.ZERO) == 0 ? "success" : "warning")
        ));
        response.put("openingTotal", currency(openingTotal));
        response.put("closingTotal", currency(closingTotal));
        response.put("differenceTotal", currency(differenceTotal));
        response.put("movementsCount", movements);
        response.put("sessions", webDataService.cashSessions(tenantId, period, store));
        response.put("movements", cashMovements(movementFilter));
        response.put("diagnostics", cashDiagnostics(sessions, movements, withoutOpenDate, lastSync(tenantId, store)));
        return response;
    }

    public Map<String, Object> finance(String tenantId, String period, String store) {
        return panelCacheService.get(panelCacheService.key("report-finance", tenantId, normalizePeriod(period), normalizeStore(store)),
                REPORT_CACHE_TTL, () -> financeUncached(tenantId, period, store));
    }

    private Map<String, Object> financeUncached(String tenantId, String period, String store) {
        initializer.ensureReady();
        Map<String, Object> finance = webDataService.finance(tenantId, period, store);
        Map<String, Object> response = baseReport("Relat\u00f3rio Financeiro", tenantId, period, store);
        response.putAll(finance);
        response.put("summaryCards", List.of(
                metric("Per\u00edodo", String.valueOf(finance.getOrDefault("periodTotal", "R$ 0,00")), "Faturamento filtrado", "success"),
                metric("M\u00eas", String.valueOf(finance.getOrDefault("monthTotal", "R$ 0,00")), "Acumulado mensal", "info"),
                metric("Lucro estimado", String.valueOf(finance.getOrDefault("profitEstimate", "R$ 0,00")), "Receita menos custo", "success"),
                metric("Canceladas", String.valueOf(finance.getOrDefault("cancelledSales", "0")), "Impacto operacional", "warning")
        ));
        return response;
    }

    public Map<String, Object> audit(String tenantId, String period, String store) {
        return panelCacheService.get(panelCacheService.key("report-audit", tenantId, normalizePeriod(period), normalizeStore(store)),
                REPORT_CACHE_TTL, () -> auditUncached(tenantId, period, store));
    }

    private Map<String, Object> auditUncached(String tenantId, String period, String store) {
        initializer.ensureReady();
        Filter filter = auditFilter(tenantId, period, store);
        long total = number("SELECT COUNT(*) FROM audit_log a WHERE " + filter.sql(), filter.args());
        long critical = number("SELECT COUNT(*) FROM audit_log a WHERE UPPER(COALESCE(a.risk_level, 'INFO')) IN ('CRITICO', 'CRITICAL') AND " + filter.sql(), filter.args());
        long warning = number("SELECT COUNT(*) FROM audit_log a WHERE UPPER(COALESCE(a.risk_level, 'INFO')) IN ('ALERTA', 'WARNING') AND " + filter.sql(), filter.args());
        Map<String, Object> response = baseReport("Relat\u00f3rio de Auditoria", tenantId, period, store);
        response.put("summaryCards", List.of(
                metric("Eventos", String.valueOf(total), "Registros auditados", "info"),
                metric("Cr\u00edticos", String.valueOf(critical), "Exigem an\u00e1lise", critical > 0 ? "danger" : "success"),
                metric("Alertas", String.valueOf(warning), "Aten\u00e7\u00e3o operacional", warning > 0 ? "warning" : "success")
        ));
        response.put("events", webDataService.auditEvents(tenantId, period, store));
        return response;
    }

    private Map<String, Object> baseReport(String title, String tenantId, String period, String store) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("title", title);
        response.put("generatedAt", LocalDateTime.now().toString());
        response.put("period", normalizePeriod(period));
        response.put("periodLabel", periodLabel(period));
        response.put("store", normalizeStore(store) == null ? "Todas as lojas" : normalizeStore(store));
        response.put("formats", List.of("PDF", "XLS", "CSV"));
        return response;
    }

    private List<Map<String, Object>> reportCards() {
        return List.of(
                reportCard("sales", "Vendas", "Vendas, ticket m\u00e9dio, formas de pagamento e top produtos por quantidade.", "/api/reports/sales"),
                reportCard("cash", "Caixa", "Aberturas, fechamentos, movimentos e diverg\u00eancias.", "/api/reports/cash"),
                reportCard("finance", "Financeiro", "Receita, lucro estimado e concilia\u00e7\u00e3o por pagamento.", "/api/reports/finance"),
                reportCard("products", "Produtos", "Cat\u00e1logo, status, categorias e disponibilidade.", "/api/reports/products"),
                reportCard("stock", "Estoque", "Ruptura, estoque baixo e movimenta\u00e7\u00f5es.", "/api/reports/stock"),
                reportCard("audit", "Auditoria", "Eventos sens\u00edveis, riscos e sincroniza\u00e7\u00e3o.", "/api/reports/audit")
        );
    }

    private Map<String, Object> reportCard(String type, String title, String description, String endpoint) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("type", type);
        card.put("title", title);
        card.put("description", description);
        card.put("endpoint", endpoint);
        card.put("formats", List.of("PDF", "XLS", "CSV"));
        return card;
    }

    private Map<String, Object> metric(String label, String value, String description, String tone) {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("label", label);
        metric.put("value", value);
        metric.put("description", description);
        metric.put("tone", tone);
        return metric;
    }

    private List<String> diagnostics(String tenantId, String period, String store) {
        List<String> diagnostics = new ArrayList<>();
        Map<String, Object> cash = cash(tenantId, period, store);
        @SuppressWarnings("unchecked")
        List<String> cashDiagnostics = (List<String>) cash.getOrDefault("diagnostics", List.of());
        diagnostics.addAll(cashDiagnostics);
        if (lastSync(tenantId, store) == null) {
            diagnostics.add("Nenhuma sincroniza\u00e7\u00e3o do PDV encontrada para o filtro atual.");
        }
        if (diagnostics.isEmpty()) {
            diagnostics.add("Dados sincronizados e relat\u00f3rios prontos para an\u00e1lise.");
        }
        return diagnostics;
    }

    private List<String> cashDiagnostics(long sessions, long movements, long withoutOpenDate, String lastSync) {
        List<String> diagnostics = new ArrayList<>();
        if (sessions == 0) {
            diagnostics.add(lastSync == null
                    ? "Nenhum caixa recebido: verifique se o PDV foi ativado e sincronizou com o Web."
                    : "Nenhum caixa encontrado no per\u00edodo. A \u00faltima sincroniza\u00e7\u00e3o foi " + lastSync + ".");
        }
        if (sessions > 0 && movements == 0) {
            diagnostics.add("Sess\u00f5es de caixa existem, mas n\u00e3o h\u00e1 movimenta\u00e7\u00f5es sincronizadas. O PDV pode n\u00e3o estar enviando cash_movements.");
        }
        if (withoutOpenDate > 0) {
            diagnostics.add(withoutOpenDate + " sess\u00e3o(\u00f5es) de caixa chegaram sem data de abertura; o painel usa uma regra de compatibilidade para exibir esses dados.");
        }
        if (diagnostics.isEmpty()) {
            diagnostics.add("Caixa sincronizado com estrutura compat\u00edvel.");
        }
        return diagnostics;
    }

    private List<Map<String, Object>> payments(Filter filter) {
        BigDecimal total = salesTotal(filter);
        return jdbcTemplate.query("""
                SELECT payment_method AS label, COALESCE(SUM(total), 0) AS value
                FROM (
                    SELECT COALESCE(NULLIF(s.payment_method, ''), 'N\u00e3o informado') AS payment_method,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - COALESCE(s.discount, 0) + COALESCE(s.surcharge, 0) AS total
                    FROM sales s
                    LEFT JOIN sale_items si ON si.tenant_id = s.tenant_id AND si.store_id = s.store_id AND si.sale_id = s.id
                    WHERE s.status = 'PAID' AND %s
                    GROUP BY s.tenant_id, s.store_id, s.id, s.payment_method, s.discount, s.surcharge
                ) totals
                GROUP BY payment_method
                ORDER BY value DESC
                """.formatted(filter.sql()), (rs, rowNum) -> chartRow(paymentName(rs.getString("label")), rs.getBigDecimal("value"), total), filter.argsArray());
    }

    private List<Map<String, Object>> revenueChart(String tenantId, String period, String store) {
        Filter filter = salesFilter(tenantId, period, store);
        String bucket = bucketLabelExpression("s.date_time", period);
        return jdbcTemplate.query("""
                SELECT label, COALESCE(SUM(total), 0) AS value
                FROM (
                    SELECT %s AS label,
                           MIN(s.date_time) AS sort_date,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - COALESCE(s.discount, 0) + COALESCE(s.surcharge, 0) AS total
                    FROM sales s
                    LEFT JOIN sale_items si ON si.tenant_id = s.tenant_id AND si.store_id = s.store_id AND si.sale_id = s.id
                    WHERE s.status = 'PAID' AND %s
                    GROUP BY %s, s.tenant_id, s.store_id, s.id, s.discount, s.surcharge
                ) totals
                GROUP BY label
                ORDER BY MIN(sort_date)
                """.formatted(bucket, filter.sql(), bucket), (rs, rowNum) -> chartRow(rs.getString("label"), rs.getBigDecimal("value"), null), filter.argsArray());
    }

    private List<Map<String, Object>> topProducts(String tenantId, String period, String store) {
        Filter filter = salesFilter(tenantId, period, store);
        return jdbcTemplate.query("""
                SELECT COALESCE(NULLIF(p.description, ''), si.product_code) AS label,
                       COALESCE(SUM(si.quantity), 0) AS quantity,
                       COUNT(DISTINCT CONCAT(s.tenant_id, ':', s.store_id, ':', s.id)) AS sales_count,
                       COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) AS value
                FROM sale_items si
                INNER JOIN sales s ON s.tenant_id = si.tenant_id AND s.store_id = si.store_id AND s.id = si.sale_id
                LEFT JOIN products p ON p.tenant_id = si.tenant_id AND p.store_id = si.store_id AND p.code = si.product_code
                WHERE s.status = 'PAID' AND %s
                GROUP BY COALESCE(NULLIF(p.description, ''), si.product_code)
                ORDER BY quantity DESC, sales_count DESC, value DESC
                LIMIT 5
                """.formatted(filter.sql()), (rs, rowNum) -> {
            BigDecimal quantity = rs.getBigDecimal("quantity");
            BigDecimal value = rs.getBigDecimal("value");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", rs.getString("label") == null || rs.getString("label").isBlank() ? "Sem informa\u00e7\u00e3o" : rs.getString("label"));
            row.put("value", safeQuantity(quantity));
            row.put("display", quantity(quantity) + " itens");
            row.put("quantity", safeQuantity(quantity));
            row.put("sales", rs.getLong("sales_count"));
            row.put("revenue", safeMoney(value));
            row.put("revenueDisplay", currency(value == null ? BigDecimal.ZERO : value));
            return row;
        }, filter.argsArray());
    }

    private List<Map<String, Object>> salesByStore(String tenantId, String period, String store) {
        Filter filter = salesFilter(tenantId, period, store);
        return jdbcTemplate.query("""
                SELECT COALESCE(NULLIF(s.source_id, ''), s.store_id) AS label,
                       COUNT(*) AS value
                FROM sales s
                WHERE %s
                GROUP BY COALESCE(NULLIF(s.source_id, ''), s.store_id)
                ORDER BY value DESC
                LIMIT 8
                """.formatted(filter.sql()), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", rs.getString("label"));
            row.put("value", rs.getLong("value"));
            row.put("display", rs.getLong("value") + " venda(s)");
            return row;
        }, filter.argsArray());
    }

    private List<Map<String, Object>> stockHealth(String tenantId, String store) {
        Filter filter = scopeFilter(tenantId, store, "p");
        long healthy = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.stock > p.min_stock AND " + filter.sql(), filter.args());
        long low = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.stock > 0 AND p.stock <= p.min_stock AND " + filter.sql(), filter.args());
        long empty = number("SELECT COUNT(*) FROM products p WHERE p.deleted_at IS NULL AND p.stock <= 0 AND " + filter.sql(), filter.args());
        return List.of(statusRow("Saud\u00e1vel", healthy, "success"), statusRow("Baixo", low, "warning"), statusRow("Zerado", empty, "danger"));
    }

    private List<Map<String, Object>> stockMovements(String tenantId, String store) {
        Filter filter = scopeFilter(tenantId, store, "sm");
        return rows("""
                SELECT sm.id, sm.product_code AS productCode, sm.type, sm.quantity, sm.previous_stock AS previousStock,
                       sm.new_stock AS newStock, sm.reason, sm.user, sm.created_at AS createdAt
                FROM stock_movements sm
                WHERE %s
                ORDER BY sm.created_at DESC, sm.id DESC
                LIMIT 80
                """.formatted(filter.sql()), filter.args());
    }

    private List<Map<String, Object>> cashMovements(Filter filter) {
        return rows("""
                SELECT cm.id, cm.session_id AS sessionId, cm.type, cm.value, cm.observation, cm.date_time AS dateTime
                FROM cash_movements cm
                WHERE %s
                ORDER BY cm.date_time DESC, cm.id DESC
                LIMIT 100
                """.formatted(filter.sql()), filter.args());
    }

    private BigDecimal salesTotal(Filter filter) {
        return money("""
                SELECT COALESCE(SUM(total), 0)
                FROM (
                    SELECT s.id,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - COALESCE(s.discount, 0) + COALESCE(s.surcharge, 0) AS total
                    FROM sales s
                    LEFT JOIN sale_items si ON si.tenant_id = s.tenant_id AND si.store_id = s.store_id AND si.sale_id = s.id
                    WHERE s.status = 'PAID' AND %s
                    GROUP BY s.tenant_id, s.store_id, s.id, s.discount, s.surcharge
                ) totals
                """.formatted(filter.sql()), filter.args());
    }

    private BigDecimal estimatedProfit(Filter filter) {
        return salesTotal(filter).subtract(money("""
                SELECT COALESCE(SUM(si.quantity * COALESCE(p.cost_price, 0)), 0)
                FROM sale_items si
                INNER JOIN sales s ON s.tenant_id = si.tenant_id AND s.store_id = si.store_id AND s.id = si.sale_id
                LEFT JOIN products p ON p.tenant_id = si.tenant_id AND p.store_id = si.store_id AND p.code = si.product_code
                WHERE s.status = 'PAID' AND %s
                """.formatted(filter.sql()), filter.args()));
    }

    private List<Map<String, Object>> rows(String sql, List<Object> args) {
        return jdbcTemplate.queryForList(sql, args.toArray()).stream().map(this::displayRow).toList();
    }

    private Map<String, Object> displayRow(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (value instanceof Timestamp timestamp) {
                row.put(key, timestamp.toLocalDateTime().toString().replace('T', ' '));
            } else if (value instanceof BigDecimal decimal && key.toLowerCase(Locale.ROOT).contains("value")) {
                row.put(key, decimal);
                row.put(key + "Display", currency(decimal));
            } else {
                row.put(key, value);
            }
        });
        return row;
    }

    private Map<String, Object> chartRow(String label, BigDecimal value, BigDecimal total) {
        BigDecimal safe = safeMoney(value);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label == null || label.isBlank() ? "Sem informa\u00e7\u00e3o" : label);
        row.put("value", safe);
        row.put("display", currency(safe));
        if (total != null && total.compareTo(BigDecimal.ZERO) > 0) {
            row.put("percent", safe.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP));
        } else {
            row.put("percent", BigDecimal.ZERO);
        }
        return row;
    }

    private BigDecimal safeMoney(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal safeQuantity(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(3, RoundingMode.HALF_UP);
    }

    private String quantity(BigDecimal value) {
        BigDecimal safe = value == null ? BigDecimal.ZERO : value;
        BigDecimal normalized = safe.stripTrailingZeros();
        NumberFormat format = NumberFormat.getNumberInstance(PT_BR);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(Math.max(0, Math.min(3, normalized.scale())));
        return format.format(normalized);
    }

    private Map<String, Object> statusRow(String label, long value, String tone) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", value);
        row.put("display", String.valueOf(value));
        row.put("tone", tone);
        return row;
    }

    private BigDecimal money(String sql, List<Object> args) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args.toArray());
        return value == null ? BigDecimal.ZERO : value;
    }

    private long number(String sql, List<Object> args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0L : value;
    }

    private String lastSync(String tenantId, String store) {
        Filter filter = scopeFilter(tenantId, store, null);
        List<String> rows = jdbcTemplate.query("""
                SELECT CAST(received_at AS CHAR)
                FROM sync_runs
                WHERE status = 'SUCCESS' AND %s
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """.formatted(filter.sql()), (rs, rowNum) -> rs.getString(1), filter.argsArray());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Filter salesFilter(String tenantId, String period, String store) {
        String normalizedStore = normalizeStore(store);
        Filter periodFilter = periodCondition("s.date_time", period, periodAnchor(period, "sales", "date_time", tenantId, normalizedStore));
        return scopeFilter(tenantId, normalizedStore, "s").and(periodFilter.sql(), periodFilter.argsArray());
    }

    private Filter cashFilter(String tenantId, String period, String store) {
        String normalizedStore = normalizeStore(store);
        Filter periodFilter = periodCondition("COALESCE(cs.opened_at, cs.closed_at)", period, periodAnchor(period, "cash_sessions", "COALESCE(opened_at, closed_at)", tenantId, normalizedStore));
        return scopeFilter(tenantId, normalizedStore, "cs").and(periodFilter.sql(), periodFilter.argsArray());
    }

    private Filter cashMovementFilter(String tenantId, String period, String store) {
        String normalizedStore = normalizeStore(store);
        Filter periodFilter = periodCondition("cm.date_time", period, periodAnchor(period, "cash_movements", "date_time", tenantId, normalizedStore));
        return scopeFilter(tenantId, normalizedStore, "cm").and(periodFilter.sql(), periodFilter.argsArray());
    }

    private Filter auditFilter(String tenantId, String period, String store) {
        String normalizedStore = normalizeStore(store);
        Filter periodFilter = periodCondition("a.created_at", period, periodAnchor(period, "audit_log", "created_at", tenantId, normalizedStore));
        return scopeFilter(tenantId, normalizedStore, "a").and(periodFilter.sql(), periodFilter.argsArray());
    }

    private Filter scopeFilter(String tenantId, String store, String alias) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder sql = new StringBuilder(column(alias, "tenant_id")).append(" = ?");
        String normalizedStore = normalizeStore(store);
        if (normalizedStore != null) {
            sql.append(" AND ").append(column(alias, "store_id")).append(" = ?");
            args.add(normalizedStore);
        }
        return new Filter(sql.toString(), args);
    }

    private Filter periodCondition(String column, String period, LocalDate anchor) {
        String normalizedPeriod = normalizePeriod(period);
        LocalDate safeAnchor = "today".equals(normalizedPeriod) || anchor == null ? today() : anchor;
        LocalDate start = switch (normalizePeriod(period)) {
            case "7d" -> safeAnchor.minusDays(6);
            case "month" -> safeAnchor.minusDays(29);
            case "year" -> safeAnchor.minusDays(364);
            default -> safeAnchor;
        };
        LocalDate end = safeAnchor.plusDays(1);
        return new Filter(column + " >= ? AND " + column + " < ?", List.of(start, end));
    }

    private LocalDate periodAnchor(String period, String table, String expression, String tenantId, String store) {
        return "today".equals(normalizePeriod(period)) ? null : anchorDate(table, expression, tenantId, store);
    }

    private LocalDate anchorDate(String table, String expression, String tenantId, String store) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder sql = new StringBuilder("SELECT DATE(MAX(")
                .append(expression)
                .append(")) FROM ")
                .append(table)
                .append(" WHERE tenant_id = ?");
        if (store != null) {
            sql.append(" AND store_id = ?");
            args.add(store);
        }
        java.sql.Date value = jdbcTemplate.queryForObject(sql.toString(), java.sql.Date.class, args.toArray());
        return value == null ? today() : value.toLocalDate();
    }

    private LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    private String bucketLabelExpression(String column, String period) {
        return switch (normalizePeriod(period)) {
            case "year" -> "DATE_FORMAT(" + column + ", '%m/%Y')";
            case "today" -> "DATE_FORMAT(" + column + ", '%H:00')";
            default -> "DATE_FORMAT(" + column + ", '%d/%m')";
        };
    }

    private String normalizePeriod(String period) {
        if (period == null) {
            return "today";
        }
        return switch (period.trim().toLowerCase(Locale.ROOT)) {
            case "7d", "7", "week" -> "7d";
            case "month", "mes", "m\u00eas", "30d", "30dias", "30 dias" -> "month";
            case "year", "ano" -> "year";
            default -> "today";
        };
    }

    private String periodLabel(String period) {
        return switch (normalizePeriod(period)) {
            case "7d" -> "7 dias";
            case "month" -> "30 dias";
            case "year" -> "1 ano";
            default -> "Hoje";
        };
    }

    private String normalizeStore(String store) {
        AuthTokenService.SessionToken session = AuthContext.current().orElse(null);
        if (store == null || store.isBlank()) {
            return session == null || canAccessAllStores(session) ? null : session.storeId();
        }
        String value = store.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.equals("all") || lower.equals("geral") || lower.equals("todos")) {
            return session == null || canAccessAllStores(session) ? null : session.storeId();
        }
        if (session != null && !canAccessAllStores(session) && !value.equals(session.storeId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não tem permissão para acessar esta loja.");
        }
        return value;
    }

    private boolean canAccessAllStores(AuthTokenService.SessionToken session) {
        if (session == null) {
            return true;
        }
        String role = session.role() == null ? "" : session.role().trim().toUpperCase(Locale.ROOT);
        boolean tenantWideRole = role.equals("DONO") || role.equals("OWNER") || role.equals("ADMIN") || role.equals("ADMINISTRADOR") || role.equals("ADMINISTRATOR");
        String store = session.storeId() == null ? "" : session.storeId().trim();
        return tenantWideRole && (store.isBlank() || "WEB".equalsIgnoreCase(store) || "all".equalsIgnoreCase(store));
    }

    private String column(String alias, String column) {
        return alias == null || alias.isBlank() ? column : alias + "." + column;
    }

    private String paymentName(String value) {
        if (value == null || value.isBlank()) {
            return "N\u00e3o informado";
        }
        return switch (value.trim().toUpperCase(Locale.ROOT)) {
            case "CASH", "DINHEIRO" -> "Dinheiro";
            case "PIX" -> "Pix";
            case "CREDIT", "CREDITO", "CARTAO_CREDITO" -> "Cart\u00e3o de cr\u00e9dito";
            case "DEBIT", "DEBITO", "CARTAO_DEBITO" -> "Cart\u00e3o de d\u00e9bito";
            default -> value;
        };
    }

    private String currency(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(PT_BR)
                .format(value == null ? BigDecimal.ZERO : value)
                .replace('\u00A0', ' ');
    }

    private static final class Filter {
        private final String sql;
        private final List<Object> args;

        private Filter(String sql, List<Object> args) {
            this.sql = sql;
            this.args = List.copyOf(args);
        }

        private Filter and(String condition) {
            if (condition == null || condition.isBlank()) {
                return this;
            }
            List<Object> copy = new ArrayList<>(args);
            return new Filter("(" + sql + ") AND (" + condition + ")", copy);
        }

        private Filter and(String condition, Object... extraArgs) {
            if (condition == null || condition.isBlank()) {
                return this;
            }
            List<Object> copy = new ArrayList<>(args);
            if (extraArgs != null) {
                copy.addAll(List.of(extraArgs));
            }
            return new Filter("(" + sql + ") AND (" + condition + ")", copy);
        }

        private String sql() {
            return sql;
        }

        private List<Object> args() {
            return args;
        }

        private Object[] argsArray() {
            return args.toArray();
        }
    }
}
