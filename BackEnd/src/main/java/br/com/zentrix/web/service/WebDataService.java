package br.com.zentrix.web.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class WebDataService {
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final Duration PANEL_CACHE_TTL = Duration.ofSeconds(20);
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("America/Sao_Paulo");
    private static final int PDV_ONLINE_WINDOW_MINUTES = 3;

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final AlertService alertService;
    private final LicenseService licenseService;
    private final SettingsService settingsService;
    private final PanelCacheService panelCacheService;

    public WebDataService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            AlertService alertService,
            LicenseService licenseService,
            SettingsService settingsService,
            PanelCacheService panelCacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.alertService = alertService;
        this.licenseService = licenseService;
        this.settingsService = settingsService;
        this.panelCacheService = panelCacheService;
    }

    public Map<String, Object> dashboard(String tenantId, String period, String store) {
        return panelCacheService.get(panelCacheService.key("dashboard", tenantId, normalizePeriod(period), normalizeStore(store)),
                PANEL_CACHE_TTL, () -> dashboardUncached(tenantId, period, store));
    }

    private Map<String, Object> dashboardUncached(String tenantId, String period, String store) {
        initializer.ensureReady();
        Filter salesFilter = salesFilter(tenantId, period, store);
        Filter productFilter = scopeFilter(tenantId, store, "p");
        BigDecimal periodTotal = salesTotal(salesFilter);
        Long paidSales = number("SELECT COUNT(*) FROM sales s WHERE s.status = 'PAID' AND " + salesFilter.sql(), salesFilter.args());
        BigDecimal averageTicket = paidSales == 0 ? BigDecimal.ZERO : periodTotal.divide(BigDecimal.valueOf(paidSales), 2, RoundingMode.HALF_UP);
        Long lowStock = number("SELECT COUNT(*) FROM products p WHERE p.stock <= p.min_stock AND " + productFilter.sql(), productFilter.args());
        Long criticalStock = number("SELECT COUNT(*) FROM products p WHERE p.stock <= 0 AND " + productFilter.sql(), productFilter.args());

        Map<String, Object> syncStatus = syncStatusSummary(tenantId, store);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("company", company(tenantId, store));
        response.put("activeStore", activeStore(tenantId, store));
        response.put("stores", stores(tenantId));
        response.put("lastSync", syncStatus.get("lastSync"));
        response.put("pdvConnected", syncStatus.get("connected"));
        response.put("pdvStatus", syncStatus.get("connectionLabel"));
        response.put("pdvLastSeen", syncStatus.get("lastSeenAt"));
        response.put("syncProgress", Boolean.TRUE.equals(syncStatus.get("connected")) ? 100 : 0);
        response.put("period", normalizePeriod(period));
        response.put("metrics", List.of(
                metric("Faturamento", currency(periodTotal), periodLabel(period), "success"),
                metric("Vendas pagas", String.valueOf(paidSales), periodLabel(period), "success"),
                metric("Ticket médio", currency(averageTicket), periodLabel(period), "warning"),
                metric("Estoque baixo", String.valueOf(lowStock), criticalStock + " críticos", "danger")
        ));
        response.put("payments", payments(periodTotal, salesFilter));
        response.put("revenueChart", revenueChart(tenantId, period, store));
        response.put("salesByStore", salesByStore(tenantId, period, store));
        response.put("stockHealth", stockHealth(tenantId, store));
        response.put("topProducts", topProducts(tenantId, period, store));
        response.put("alerts", alertService.alerts(tenantId, store));
        response.put("license", licenseService.current(tenantId));
        response.put("syncStatus", syncStatus);
        response.put("cashSummary", cashSummary(tenantId, period, store));
        response.put("financeSummary", financeSummary(tenantId, period, store));
        response.put("cancelledSales", number("SELECT COUNT(*) FROM sales s WHERE s.status = 'CANCELLED' AND " + salesFilter.sql(), salesFilter.args()));
        response.put("profitEstimate", currency(estimatedProfit(salesFilter)));
        response.put("lowStockCount", lowStock);
        response.put("criticalStockCount", criticalStock);
        return response;
    }

    private BigDecimal salesTotal(Filter filter) {
        return money("""
                SELECT COALESCE(SUM(total), 0)
                FROM (
                    SELECT s.tenant_id, s.store_id, s.id,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                    FROM sales s
                    LEFT JOIN sale_items si
                      ON si.tenant_id = s.tenant_id
                     AND si.store_id = s.store_id
                     AND si.sale_id = s.id
                    WHERE s.status = 'PAID' AND %s
                    GROUP BY s.tenant_id, s.store_id, s.id, s.discount, s.surcharge
                ) totals
                """.formatted(filter.sql()), filter.args());
    }

    public List<Map<String, Object>> sales(String tenantId, String period, String store) {
        return sales(tenantId, period, store, 50, 0);
    }

    public List<Map<String, Object>> sales(String tenantId, String period, String store, int limit, int offset) {
        return panelCacheService.get(panelCacheService.key("sales", tenantId, normalizePeriod(period), normalizeStore(store), safeLimit(limit, 50, 500), safeOffset(offset)),
                PANEL_CACHE_TTL, () -> salesUncached(tenantId, period, store, limit, offset));
    }

    private List<Map<String, Object>> salesUncached(String tenantId, String period, String store, int limit, int offset) {
        initializer.ensureReady();
        Filter filter = salesFilter(tenantId, period, store);
        int safeLimit = safeLimit(limit, 50, 500);
        int safeOffset = safeOffset(offset);
        return jdbcTemplate.query("""
                SELECT s.tenant_id, s.store_id, s.source_id, s.id, s.operator, s.payment_method, s.status, s.date_time,
                       COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                FROM sales s
                LEFT JOIN sale_items si
                  ON si.tenant_id = s.tenant_id
                 AND si.store_id = s.store_id
                 AND si.sale_id = s.id
                WHERE %s
                GROUP BY s.tenant_id, s.store_id, s.source_id, s.id, s.operator, s.payment_method, s.status, s.date_time, s.discount, s.surcharge
                ORDER BY s.date_time DESC, s.id DESC
                LIMIT ? OFFSET ?
                """.formatted(filter.sql()), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", "ZV-" + rs.getInt("id"));
            row.put("store", storeDisplayName(rs.getString("source_id"), rs.getString("store_id")));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("storeId", rs.getString("store_id"));
            row.put("time", rs.getTimestamp("date_time") == null ? "-" : rs.getTimestamp("date_time").toLocalDateTime().toString().replace('T', ' '));
            row.put("operator", rs.getString("operator"));
            row.put("payment", paymentName(rs.getString("payment_method")));
            row.put("status", statusName(rs.getString("status")));
            row.put("total", currency(rs.getBigDecimal("total")));
            return row;
        }, pagedArgs(filter, safeLimit, safeOffset));
    }

    public List<Map<String, Object>> products(String tenantId, String store) {
        return products(tenantId, store, 100, 0);
    }

    public List<Map<String, Object>> products(String tenantId, String store, int limit, int offset) {
        initializer.ensureReady();
        Filter filter = scopeFilter(tenantId, store, "p");
        int safeLimit = safeLimit(limit, 100, 500);
        int safeOffset = safeOffset(offset);
        return jdbcTemplate.query("""
                SELECT p.tenant_id, p.store_id, p.source_id, p.code, p.description, p.unit, p.price, p.cost_price,
                       p.stock, p.min_stock, p.ideal_stock, p.category, p.barcode, p.active, p.updated_at, p.deleted_at
                FROM products p
                WHERE p.deleted_at IS NULL AND %s
                ORDER BY p.description
                LIMIT ? OFFSET ?
                """.formatted(filter.sql()), (rs, rowNum) -> productRow(
                rs.getString("tenant_id"),
                rs.getString("store_id"),
                rs.getString("source_id"),
                rs.getString("description"),
                rs.getString("code"),
                rs.getString("unit"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("cost_price"),
                rs.getBigDecimal("stock"),
                rs.getBigDecimal("min_stock"),
                rs.getBigDecimal("ideal_stock"),
                rs.getString("category"),
                rs.getString("barcode"),
                rs.getBoolean("active"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toString()
        ), pagedArgs(filter, safeLimit, safeOffset));
    }

    public List<Map<String, Object>> cashSessions(String tenantId, String period, String store) {
        return cashSessions(tenantId, period, store, 50, 0);
    }

    public List<Map<String, Object>> cashSessions(String tenantId, String period, String store, int limit, int offset) {
        return panelCacheService.get(panelCacheService.key("cash-sessions", tenantId, normalizePeriod(period), normalizeStore(store), safeLimit(limit, 50, 300), safeOffset(offset)),
                PANEL_CACHE_TTL, () -> cashSessionsUncached(tenantId, period, store, limit, offset));
    }

    private List<Map<String, Object>> cashSessionsUncached(String tenantId, String period, String store, int limit, int offset) {
        initializer.ensureReady();
        Filter filter = cashFilter(tenantId, period, store);
        int safeLimit = safeLimit(limit, 50, 300);
        int safeOffset = safeOffset(offset);
        return jdbcTemplate.query("""
                SELECT cs.tenant_id, cs.store_id, cs.source_id, cs.id, cs.cash_id, cs.operator,
                       cs.opening_balance, cs.closing_balance, cs.expected_balance, cs.difference,
                       cs.opened_at, cs.closed_at, cs.is_open, cs.status
                FROM cash_sessions cs
                WHERE %s
                ORDER BY COALESCE(cs.opened_at, cs.closed_at) DESC, cs.id DESC
                LIMIT ? OFFSET ?
                """.formatted(filter.sql()), (rs, rowNum) -> {
            boolean closed = rs.getTimestamp("closed_at") != null
                    || !rs.getBoolean("is_open")
                    || "CLOSED".equalsIgnoreCase(rs.getString("status"))
                    || "FECHADO".equalsIgnoreCase(rs.getString("status"));
            BigDecimal expected = rs.getBigDecimal("expected_balance") == null ? rs.getBigDecimal("opening_balance") : rs.getBigDecimal("expected_balance");
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("store", storeDisplayName(rs.getString("source_id"), rs.getString("store_id")));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("storeId", rs.getString("store_id"));
            row.put("code", rs.getString("cash_id") == null ? "CX-" + rs.getInt("id") : rs.getString("cash_id"));
            row.put("operator", rs.getString("operator"));
            row.put("openedAt", rs.getTimestamp("opened_at") == null ? "-" : rs.getTimestamp("opened_at").toLocalDateTime().toString().replace('T', ' '));
            row.put("closedAt", rs.getTimestamp("closed_at") == null ? (closed ? "Fechado sem data" : "Aberto") : rs.getTimestamp("closed_at").toLocalDateTime().toString().replace('T', ' '));
            row.put("status", closed ? "Fechado" : "Aberto");
            row.put("statusRaw", rs.getString("status"));
            row.put("expected", currency(expected));
            row.put("openingBalance", currency(rs.getBigDecimal("opening_balance")));
            row.put("informed", rs.getBigDecimal("closing_balance") == null ? "-" : currency(rs.getBigDecimal("closing_balance")));
            row.put("difference", rs.getBigDecimal("difference") == null ? "-" : currency(rs.getBigDecimal("difference")));
            return row;
        }, pagedArgs(filter, safeLimit, safeOffset));
    }

    public List<Map<String, Object>> stockAlerts(String tenantId, String store) {
        return stockAlerts(tenantId, store, 100, 0);
    }

    public List<Map<String, Object>> stockAlerts(String tenantId, String store, int limit, int offset) {
        initializer.ensureReady();
        Filter filter = scopeFilter(tenantId, store, "p");
        int safeLimit = safeLimit(limit, 100, 500);
        int safeOffset = safeOffset(offset);
        return jdbcTemplate.query("""
                SELECT p.tenant_id, p.store_id, p.source_id, p.code, p.description, p.unit, p.price, p.cost_price,
                       p.stock, p.min_stock, p.ideal_stock, p.category, p.barcode, p.active, p.updated_at, p.deleted_at
                FROM products p
                WHERE p.deleted_at IS NULL AND p.active = TRUE AND p.stock <= p.min_stock AND %s
                ORDER BY p.stock ASC, p.description
                LIMIT ? OFFSET ?
                """.formatted(filter.sql()), (rs, rowNum) -> productRow(
                rs.getString("tenant_id"),
                rs.getString("store_id"),
                rs.getString("source_id"),
                rs.getString("description"),
                rs.getString("code"),
                rs.getString("unit"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("cost_price"),
                rs.getBigDecimal("stock"),
                rs.getBigDecimal("min_stock"),
                rs.getBigDecimal("ideal_stock"),
                rs.getString("category"),
                rs.getString("barcode"),
                rs.getBoolean("active"),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toString()
        ), pagedArgs(filter, safeLimit, safeOffset));
    }

    public List<Map<String, Object>> auditEvents(String tenantId, String period, String store) {
        return auditEvents(tenantId, period, store, 50, 0);
    }

    public List<Map<String, Object>> auditEvents(String tenantId, String period, String store, int limit, int offset) {
        initializer.ensureReady();
        Filter filter = auditFilter(tenantId, period, store);
        int safeLimit = safeLimit(limit, 50, 500);
        int safeOffset = safeOffset(offset);
        return jdbcTemplate.query("""
                SELECT a.tenant_id, a.store_id, a.source_id, a.acao, a.usuario, a.entity_type, a.entity_id, a.details,
                       a.risk_level, a.reason, a.origin, a.created_at
                FROM audit_log a
                WHERE %s
                ORDER BY a.created_at DESC, a.id DESC
                LIMIT ? OFFSET ?
                """.formatted(filter.sql()), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("store", storeDisplayName(rs.getString("source_id"), rs.getString("store_id")));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("storeId", rs.getString("store_id"));
            row.put("action", rs.getString("acao"));
            row.put("time", rs.getTimestamp("created_at") == null ? "-" : rs.getTimestamp("created_at").toLocalDateTime().toLocalTime().toString());
            row.put("user", rs.getString("usuario"));
            row.put("description", rs.getString("entity_type") + " " + rs.getString("entity_id"));
            row.put("value", rs.getString("details"));
            row.put("riskLevel", rs.getString("risk_level"));
            row.put("reason", rs.getString("reason"));
            row.put("origin", rs.getString("origin"));
            return row;
        }, pagedArgs(filter, safeLimit, safeOffset));
    }

    public List<Map<String, Object>> backups(String tenantId, String store) {
        return backups(tenantId, store, 20, 0);
    }

    public List<Map<String, Object>> backups(String tenantId, String store, int limit, int offset) {
        initializer.ensureReady();
        Filter filter = scopeFilter(tenantId, store, null);
        int safeLimit = safeLimit(limit, 20, 200);
        int safeOffset = safeOffset(offset);
        return jdbcTemplate.query("""
                SELECT id, created_at, finished_at, tenant_id, store_id, device_id, source_id, total_rows, status,
                       file_name, file_size_bytes, checksum_sha256, created_by, backup_type, file_path, message
                FROM backup_runs
                WHERE %s
                ORDER BY created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """.formatted(filter.sql()), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("date", rs.getTimestamp("created_at") == null ? "-" : rs.getTimestamp("created_at").toString());
            row.put("finishedAt", rs.getTimestamp("finished_at") == null ? null : rs.getTimestamp("finished_at").toString());
            row.put("origin", storeDisplayName(rs.getString("source_id"), rs.getString("store_id")));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("storeId", rs.getString("store_id"));
            row.put("deviceId", rs.getString("device_id"));
            row.put("sourceId", rs.getString("source_id"));
            row.put("fileName", rs.getString("file_name"));
            row.put("size", humanBytes(rs.getLong("file_size_bytes")));
            row.put("rows", rs.getInt("total_rows"));
            String checksum = rs.getString("checksum_sha256");
            String filePath = rs.getString("file_path");
            boolean fileExists = filePath != null && !filePath.isBlank() && Files.isRegularFile(Path.of(filePath));
            boolean checksumValid = fileExists && (checksum == null || checksum.isBlank() || checksum.equalsIgnoreCase(sha256(Path.of(filePath))));
            row.put("checksum", checksum);
            row.put("fileExists", fileExists);
            row.put("checksumValid", checksumValid);
            row.put("integrity", backupIntegrityLabel(rs.getString("status"), fileExists, checksumValid));
            row.put("createdBy", rs.getString("created_by"));
            row.put("type", rs.getString("backup_type"));
            row.put("message", rs.getString("message"));
            row.put("status", rs.getString("status"));
            return row;
        }, pagedArgs(filter, safeLimit, safeOffset));
    }

    public List<Map<String, Object>> clients(String tenantId, String store) {
        return clients(tenantId, store, 100, 0);
    }

    public List<Map<String, Object>> clients(String tenantId, String store, int limit, int offset) {
        initializer.ensureReady();
        Filter filter = scopeFilter(tenantId, store, "c");
        int safeLimit = safeLimit(limit, 100, 500);
        int safeOffset = safeOffset(offset);
        return jdbcTemplate.query("""
                SELECT c.tenant_id, c.store_id, c.source_id, c.id, c.name, c.cpf_cnpj, c.phone, c.email, c.address,
                       c.created_at, c.birth_date, c.active, c.notes, c.loyalty_points,
                       COALESCE(SUM(CASE WHEN s.status = 'PAID' THEN st.total ELSE 0 END), 0) AS total_spent,
                       MAX(CASE WHEN s.status = 'PAID' THEN s.date_time ELSE NULL END) AS last_purchase,
                       COUNT(CASE WHEN s.status = 'PAID' THEN 1 ELSE NULL END) AS purchase_count
                FROM clients c
                LEFT JOIN sales s ON s.tenant_id = c.tenant_id AND s.store_id = c.store_id AND CAST(c.id AS CHAR) = CAST(s.id AS CHAR)
                LEFT JOIN (
                    SELECT si.tenant_id, si.store_id, si.sale_id,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) AS total
                    FROM sale_items si
                    GROUP BY si.tenant_id, si.store_id, si.sale_id
                ) st ON st.tenant_id = s.tenant_id AND st.store_id = s.store_id AND st.sale_id = s.id
                WHERE %s
                GROUP BY c.tenant_id, c.store_id, c.source_id, c.id, c.name, c.cpf_cnpj, c.phone, c.email, c.address,
                         c.created_at, c.birth_date, c.active, c.notes, c.loyalty_points
                ORDER BY c.name
                LIMIT ? OFFSET ?
                """.formatted(filter.sql()), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("store", storeDisplayName(rs.getString("source_id"), rs.getString("store_id")));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("storeId", rs.getString("store_id"));
            row.put("name", rs.getString("name"));
            row.put("cpfCnpj", rs.getString("cpf_cnpj"));
            row.put("phone", rs.getString("phone"));
            row.put("email", rs.getString("email"));
            row.put("address", rs.getString("address"));
            row.put("createdAt", rs.getTimestamp("created_at") == null ? "-" : rs.getTimestamp("created_at").toString());
            row.put("birthDate", rs.getDate("birth_date") == null ? null : rs.getDate("birth_date").toString());
            row.put("active", rs.getBoolean("active"));
            row.put("notes", rs.getString("notes"));
            row.put("loyaltyPoints", rs.getInt("loyalty_points"));
            row.put("totalSpent", currency(rs.getBigDecimal("total_spent")));
            row.put("lastPurchase", rs.getTimestamp("last_purchase") == null ? null : rs.getTimestamp("last_purchase").toString());
            row.put("purchaseCount", rs.getLong("purchase_count"));
            row.put("averageTicket", rs.getLong("purchase_count") <= 0 ? currency(BigDecimal.ZERO) : currency(rs.getBigDecimal("total_spent").divide(BigDecimal.valueOf(rs.getLong("purchase_count")), 2, RoundingMode.HALF_UP)));
            row.put("status", rs.getBoolean("active") ? "Ativo" : "Inativo");
            return row;
        }, pagedArgs(filter, safeLimit, safeOffset));
    }

    public List<Map<String, Object>> employees(String tenantId, String store) {
        return employees(tenantId, store, 100, 0);
    }

    public List<Map<String, Object>> employees(String tenantId, String store, int limit, int offset) {
        initializer.ensureReady();
        Filter filter = scopeFilter(tenantId, store, "u");
        int safeLimit = safeLimit(limit, 100, 500);
        int safeOffset = safeOffset(offset);
        return jdbcTemplate.query("""
                SELECT u.tenant_id, u.store_id, u.source_id, u.username, u.display_name, u.role, u.active, u.created_at, u.updated_at, u.last_login_at, u.permissions_json
                FROM users u
                WHERE %s
                ORDER BY u.display_name
                LIMIT ? OFFSET ?
                """.formatted(filter.sql()), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("store", storeDisplayName(rs.getString("source_id"), rs.getString("store_id")));
            row.put("tenantId", rs.getString("tenant_id"));
            row.put("storeId", rs.getString("store_id"));
            row.put("name", rs.getString("display_name"));
            row.put("username", rs.getString("username"));
            row.put("role", rs.getString("role"));
            row.put("active", rs.getBoolean("active") ? "Ativo" : "Inativo");
            row.put("createdAt", rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toString());
            row.put("updatedAt", rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toString());
            row.put("lastLoginAt", rs.getTimestamp("last_login_at") == null ? null : rs.getTimestamp("last_login_at").toString());
            row.put("permissionsJson", rs.getString("permissions_json"));
            return row;
        }, pagedArgs(filter, safeLimit, safeOffset));
    }

    public Map<String, Object> finance(String tenantId, String period, String store) {
        return panelCacheService.get(panelCacheService.key("finance", tenantId, normalizePeriod(period), normalizeStore(store)),
                PANEL_CACHE_TTL, () -> financeUncached(tenantId, period, store));
    }

    private Map<String, Object> financeUncached(String tenantId, String period, String store) {
        initializer.ensureReady();
        Filter salesFilter = salesFilter(tenantId, period, store);
        Filter monthFilter = salesFilter(tenantId, "month", store);
        Filter entryFilter = financialEntryFilter(tenantId, period, store);
        BigDecimal periodTotal = salesTotal(salesFilter);
        BigDecimal monthTotal = salesTotal(monthFilter);
        BigDecimal manualRevenue = financialEntryTotal(entryFilter, "RECEITA", "PAGO");
        BigDecimal manualExpenses = financialEntryTotal(entryFilter, "DESPESA", "PAGO");
        BigDecimal pendingReceivable = financialEntryTotal(entryFilter, "RECEITA", "PENDENTE");
        BigDecimal pendingPayable = financialEntryTotal(entryFilter, "DESPESA", "PENDENTE");
        BigDecimal netTotal = periodTotal.add(manualRevenue).subtract(manualExpenses);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("activeStore", activeStore(tenantId, store));
        response.put("todayTotal", currency(periodTotal));
        response.put("periodTotal", currency(periodTotal));
        response.put("periodLabel", periodLabel(period));
        response.put("monthTotal", currency(monthTotal));
        response.put("manualRevenue", currency(manualRevenue));
        response.put("manualExpenses", currency(manualExpenses));
        response.put("pendingReceivable", currency(pendingReceivable));
        response.put("pendingPayable", currency(pendingPayable));
        response.put("netTotal", currency(netTotal));
        response.put("paidSales", number("SELECT COUNT(*) FROM sales s WHERE s.status = 'PAID' AND " + salesFilter.sql(), salesFilter.args()));
        response.put("cancelledSales", number("SELECT COUNT(*) FROM sales s WHERE s.status = 'CANCELLED' AND " + salesFilter.sql(), salesFilter.args()));
        response.put("payments", payments(periodTotal, salesFilter));
        response.put("revenueChart", revenueChart(tenantId, period, store));
        response.put("salesByStore", salesByStore(tenantId, period, store));
        response.put("financeSummary", financeSummary(tenantId, period, store));
        response.put("profitEstimate", currency(estimatedProfit(salesFilter)));
        return response;
    }

    public Map<String, Object> reports(String tenantId, String period, String store) {
        return panelCacheService.get(panelCacheService.key("reports", tenantId, normalizePeriod(period), normalizeStore(store)),
                PANEL_CACHE_TTL, () -> reportsUncached(tenantId, period, store));
    }

    private Map<String, Object> reportsUncached(String tenantId, String period, String store) {
        initializer.ensureReady();
        Filter salesFilter = salesFilter(tenantId, period, store);
        Filter productFilter = scopeFilter(tenantId, store, "p");
        Filter clientsFilter = scopeFilter(tenantId, store, "c");
        Filter cashFilter = cashFilter(tenantId, period, store);
        Filter auditFilter = auditFilter(tenantId, period, store);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("period", normalizePeriod(period));
        response.put("activeStore", activeStore(tenantId, store));
        response.put("sales", number("SELECT COUNT(*) FROM sales s WHERE " + salesFilter.sql(), salesFilter.args()));
        response.put("products", number("SELECT COUNT(*) FROM products p WHERE " + productFilter.sql(), productFilter.args()));
        response.put("clients", number("SELECT COUNT(*) FROM clients c WHERE " + clientsFilter.sql(), clientsFilter.args()));
        response.put("cashSessions", number("SELECT COUNT(*) FROM cash_sessions cs WHERE " + cashFilter.sql(), cashFilter.args()));
        response.put("stockAlerts", number("SELECT COUNT(*) FROM products p WHERE p.stock <= p.min_stock AND " + productFilter.sql(), productFilter.args()));
        response.put("auditEvents", number("SELECT COUNT(*) FROM audit_log a WHERE " + auditFilter.sql(), auditFilter.args()));
        response.put("lastSync", lastSync(tenantId, store));
        response.put("revenueChart", revenueChart(tenantId, period, store));
        response.put("salesByStore", salesByStore(tenantId, period, store));
        response.put("stockHealth", stockHealth(tenantId, store));
        response.put("topProducts", topProducts(tenantId, period, store));
        response.put("availableReports", availableReports());
        return response;
    }

    public Map<String, Object> settings(String tenantId, String store) {
        initializer.ensureReady();
        Filter userFilter = scopeFilter(tenantId, store, "u");
        Filter productFilter = scopeFilter(tenantId, store, "p");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("database", "zentrix_web");
        response.put("api", "Painel online Zentrix");
        response.put("tenant", tenant(tenantId));
        response.put("activeStore", activeStore(tenantId, store));
        response.put("stores", stores(tenantId));
        response.put("sourceId", lastSourceId(tenantId, store));
        response.put("lastSync", lastSync(tenantId, store));
        response.put("users", number("SELECT COUNT(*) FROM users u WHERE " + userFilter.sql(), userFilter.args()));
        response.put("products", number("SELECT COUNT(*) FROM products p WHERE " + productFilter.sql(), productFilter.args()));
        response.put("devices", number("SELECT COUNT(*) FROM tenant_devices WHERE tenant_id = ?", List.of(tenantId)));
        response.put("settings", settingsService.publicSettings(tenantId, store));
        response.put("license", licenseService.current(tenantId));
        response.put("syncStatus", syncStatusSummary(tenantId, store));
        return response;
    }

    public List<Map<String, Object>> stores(String tenantId) {
        initializer.ensureReady();
        List<Map<String, Object>> response = new ArrayList<>();
        Map<String, Object> all = new LinkedHashMap<>();
        all.put("id", "all");
        all.put("name", "Geral");
        all.put("label", "Todas as lojas");
        all.put("isAll", true);
        response.add(all);

        List<Map<String, Object>> stores = jdbcTemplate.query("""
                SELECT store_id, MAX(name) AS name, MAX(source_id) AS source_id, MAX(last_sync) AS last_sync, SUM(total_rows) AS total_rows
                FROM (
                    SELECT id AS store_id, name, source_id, NULL AS last_sync, 0 AS total_rows
                    FROM tenant_stores
                    WHERE tenant_id = ?
                    UNION ALL
                    SELECT store_id, store_id AS name, source_id, MAX(received_at) AS last_sync, SUM(total_rows) AS total_rows
                    FROM sync_runs
                    WHERE tenant_id = ?
                    GROUP BY store_id, source_id
                    UNION ALL
                    SELECT store_id, store_id AS name, source_id, NULL AS last_sync, 0 AS total_rows
                    FROM products
                    WHERE tenant_id = ?
                    GROUP BY store_id, source_id
                    UNION ALL
                    SELECT store_id, store_id AS name, source_id, NULL AS last_sync, 0 AS total_rows
                    FROM sales
                    WHERE tenant_id = ?
                    GROUP BY store_id, source_id
                ) stores
                WHERE store_id IS NOT NULL AND store_id <> ''
                GROUP BY store_id
                ORDER BY MAX(last_sync) DESC, MAX(name), store_id
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            String storeId = rs.getString("store_id");
            String sourceId = rs.getString("source_id");
            row.put("id", storeId);
            row.put("name", storeDisplayName(rs.getString("name"), storeId));
            row.put("label", sourceId == null || sourceId.isBlank() ? storeId : sourceId);
            row.put("sourceId", sourceId);
            row.put("lastSync", rs.getTimestamp("last_sync") == null ? null : rs.getTimestamp("last_sync").toString());
            row.put("totalRows", rs.getLong("total_rows"));
            row.put("isAll", false);
            return row;
        }, tenantId, tenantId, tenantId, tenantId);

        response.addAll(deduplicateStores(stores));
        return response;
    }

    private List<Map<String, Object>> payments(BigDecimal periodTotal, Filter filter) {
        return jdbcTemplate.query("""
                SELECT s.payment_method,
                       COALESCE(SUM(total), 0) AS total
                FROM (
                    SELECT s.tenant_id, s.store_id, s.id, s.payment_method,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                    FROM sales s
                    LEFT JOIN sale_items si
                      ON si.tenant_id = s.tenant_id
                     AND si.store_id = s.store_id
                     AND si.sale_id = s.id
                    WHERE s.status = 'PAID' AND %s
                    GROUP BY s.tenant_id, s.store_id, s.id, s.payment_method, s.discount, s.surcharge
                ) s
                GROUP BY s.payment_method
                ORDER BY total DESC
                """.formatted(filter.sql()), (rs, rowNum) -> {
            BigDecimal total = rs.getBigDecimal("total") == null ? BigDecimal.ZERO : rs.getBigDecimal("total");
            int percent = periodTotal.compareTo(BigDecimal.ZERO) == 0
                    ? 0
                    : total.multiply(BigDecimal.valueOf(100)).divide(periodTotal, 0, RoundingMode.HALF_UP).intValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", paymentName(rs.getString("payment_method")));
            row.put("percent", percent);
            row.put("total", currency(total));
            row.put("value", total.setScale(2, RoundingMode.HALF_UP));
            return row;
        }, filter.argsArray());
    }

    private List<Map<String, Object>> revenueChart(String tenantId, String period, String store) {
        Filter filter = salesFilter(tenantId, period, store);
        String labelExpression = bucketLabelExpression("s.date_time", period);
        return jdbcTemplate.query("""
                SELECT label, COALESCE(SUM(total), 0) AS total
                FROM (
                    SELECT s.tenant_id, s.store_id, s.id, %s AS label, MIN(s.date_time) AS sort_key,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                    FROM sales s
                    LEFT JOIN sale_items si
                      ON si.tenant_id = s.tenant_id
                     AND si.store_id = s.store_id
                     AND si.sale_id = s.id
                    WHERE s.status = 'PAID' AND %s
                    GROUP BY s.tenant_id, s.store_id, s.id, s.date_time, s.discount, s.surcharge
                ) totals
                GROUP BY label
                ORDER BY MIN(sort_key)
                """.formatted(labelExpression, filter.sql()), (rs, rowNum) -> chartRow(
                rs.getString("label"),
                rs.getBigDecimal("total")
        ), filter.argsArray());
    }

    private List<Map<String, Object>> salesByStore(String tenantId, String period, String store) {
        Filter filter = salesFilter(tenantId, period, store);
        return jdbcTemplate.query("""
                SELECT store_id, source_id, COUNT(*) AS sales_count, COALESCE(SUM(total), 0) AS total
                FROM (
                    SELECT s.tenant_id, s.store_id, s.source_id, s.id,
                           COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                    FROM sales s
                    LEFT JOIN sale_items si
                      ON si.tenant_id = s.tenant_id
                     AND si.store_id = s.store_id
                     AND si.sale_id = s.id
                    WHERE s.status = 'PAID' AND %s
                    GROUP BY s.tenant_id, s.store_id, s.source_id, s.id, s.discount, s.surcharge
                ) totals
                GROUP BY store_id, source_id
                ORDER BY total DESC
                LIMIT 8
                """.formatted(filter.sql()), (rs, rowNum) -> {
            Map<String, Object> row = chartRow(storeDisplayName(rs.getString("source_id"), rs.getString("store_id")), rs.getBigDecimal("total"));
            row.put("sales", rs.getLong("sales_count"));
            row.put("storeId", rs.getString("store_id"));
            row.put("sourceId", rs.getString("source_id"));
            return row;
        }, filter.argsArray());
    }

    private List<Map<String, Object>> topProducts(String tenantId, String period, String store) {
        Filter filter = salesFilter(tenantId, period, store);
        return jdbcTemplate.query("""
                SELECT si.product_code,
                       COALESCE(p.description, si.product_code) AS description,
                       COALESCE(SUM(si.quantity), 0) AS quantity,
                       COUNT(DISTINCT CONCAT(s.tenant_id, ':', s.store_id, ':', s.id)) AS sales_count,
                       COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) AS total
                FROM sale_items si
                INNER JOIN sales s
                   ON s.tenant_id = si.tenant_id
                  AND s.store_id = si.store_id
                  AND s.id = si.sale_id
                LEFT JOIN products p
                  ON p.tenant_id = si.tenant_id
                 AND p.store_id = si.store_id
                 AND p.code = si.product_code
                WHERE s.status = 'PAID' AND %s
                GROUP BY si.product_code, COALESCE(p.description, si.product_code)
                ORDER BY quantity DESC, sales_count DESC, total DESC
                LIMIT 5
                """.formatted(filter.sql()), (rs, rowNum) -> {
            BigDecimal quantity = rs.getBigDecimal("quantity");
            BigDecimal total = rs.getBigDecimal("total");
            Map<String, Object> row = new LinkedHashMap<>();
            String description = rs.getString("description");
            row.put("label", description == null || description.isBlank() ? "Sem data" : description);
            row.put("value", safeQuantity(quantity));
            row.put("display", quantity(quantity) + " itens");
            row.put("code", rs.getString("product_code"));
            row.put("quantity", safeQuantity(quantity));
            row.put("sales", rs.getLong("sales_count"));
            row.put("revenue", safeMoney(total));
            row.put("revenueDisplay", currency(total == null ? BigDecimal.ZERO : total));
            return row;
        }, filter.argsArray());
    }

    private List<Map<String, Object>> stockHealth(String tenantId, String store) {
        Filter filter = scopeFilter(tenantId, store, "p");
        Map<String, Object> counts = jdbcTemplate.queryForMap("""
                SELECT
                    COALESCE(SUM(CASE WHEN p.stock <= 0 THEN 1 ELSE 0 END), 0) AS empty_count,
                    COALESCE(SUM(CASE WHEN p.stock > 0 AND p.stock <= p.min_stock THEN 1 ELSE 0 END), 0) AS low_count,
                    COALESCE(SUM(CASE WHEN p.stock > p.min_stock THEN 1 ELSE 0 END), 0) AS ok_count
                FROM products p
                WHERE %s
                """.formatted(filter.sql()), filter.argsArray());
        return List.of(
                chartStatusRow("Saudável", counts.get("ok_count"), "success"),
                chartStatusRow("Baixo", counts.get("low_count"), "warning"),
                chartStatusRow("Zerado", counts.get("empty_count"), "danger")
        );
    }

    private Map<String, Object> productRow(
            String tenantId,
            String storeId,
            String sourceId,
            String name,
            String code,
            String unit,
            BigDecimal price,
            BigDecimal costPrice,
            BigDecimal stock,
            BigDecimal minStock,
            BigDecimal idealStock,
            String category,
            String barcode,
            boolean active,
            String updatedAt
    ) {
        BigDecimal safeStock = stock == null ? BigDecimal.ZERO : stock;
        BigDecimal safeMinStock = minStock == null ? BigDecimal.ZERO : minStock;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", tenantId);
        row.put("storeId", storeId);
        row.put("store", storeDisplayName(sourceId, storeId));
        row.put("sourceId", sourceId);
        row.put("name", name);
        row.put("code", code);
        row.put("category", category == null || category.isBlank() ? (unit == null ? "UN" : unit) : category);
        row.put("unit", unit == null ? "UN" : unit);
        row.put("barcode", barcode);
        row.put("price", currency(price));
        row.put("costPrice", currency(costPrice));
        row.put("currentStock", safeStock.setScale(0, RoundingMode.DOWN).intValue());
        row.put("minimumStock", safeMinStock.setScale(0, RoundingMode.DOWN).intValue());
        row.put("idealStock", idealStock == null ? 0 : idealStock.setScale(0, RoundingMode.DOWN).intValue());
        row.put("active", active);
        row.put("updatedAt", updatedAt);
        row.put("status", !active ? "Inativo" : safeStock.compareTo(BigDecimal.ZERO) <= 0 ? "Sem estoque" : safeStock.compareTo(safeMinStock) <= 0 ? "Estoque baixo" : "Ativo");
        return row;
    }

    private Map<String, Object> syncStatusSummary(String tenantId, String store) {
        Filter filter = scopeFilter(tenantId, store, null);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT source_id, device_id, status, total_rows, table_counts_json, received_at, finished_at
                FROM sync_runs
                WHERE %s
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """.formatted(filter.sql()), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lastSync", rs.getTimestamp("received_at") == null ? null : rs.getTimestamp("received_at").toString());
            row.put("lastSourceId", rs.getString("source_id"));
            row.put("lastDeviceId", rs.getString("device_id"));
            row.put("status", rs.getString("status"));
            row.put("totalRows", rs.getLong("total_rows"));
            row.put("tables", rs.getString("table_counts_json"));
            row.put("progress", "SUCCESS".equalsIgnoreCase(rs.getString("status")) ? 100 : 50);
            return row;
        }, filter.argsArray());
        Map<String, Object> summary = rows.isEmpty() ? new LinkedHashMap<>() : rows.get(0);
        summary.putIfAbsent("status", "WAITING");
        summary.putIfAbsent("progress", 0);
        summary.put("recentFailures", number("SELECT COUNT(*) FROM sync_runs WHERE status <> 'SUCCESS' AND " + filter.sql() + " AND received_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)", filter.args()));
        Filter deviceFilter = scopeFilter(tenantId, store, "td");
        List<Map<String, Object>> devices = jdbcTemplate.query("""
                SELECT td.source_id, td.id AS device_id, td.last_seen_at,
                       CASE WHEN td.last_seen_at >= DATE_SUB(NOW(), INTERVAL ? MINUTE) THEN 1 ELSE 0 END AS connected
                FROM tenant_devices td
                WHERE %s
                ORDER BY td.last_seen_at DESC
                LIMIT 1
                """.formatted(deviceFilter.sql()), (rs, rowNum) -> {
            Map<String, Object> device = new LinkedHashMap<>();
            device.put("lastSourceId", rs.getString("source_id"));
            device.put("lastDeviceId", rs.getString("device_id"));
            device.put("lastSeenAt", rs.getTimestamp("last_seen_at") == null ? null : rs.getTimestamp("last_seen_at").toString());
            device.put("connected", rs.getInt("connected") == 1);
            return device;
        }, withFirstArg(PDV_ONLINE_WINDOW_MINUTES, deviceFilter.argsArray()));
        Map<String, Object> device = devices.isEmpty() ? Map.of("connected", false) : devices.get(0);
        boolean connected = Boolean.TRUE.equals(device.get("connected"));
        summary.put("connected", connected);
        summary.put("connectionLabel", connected ? "PDV conectado" : "PDV desconectado");
        summary.put("lastSeenAt", device.get("lastSeenAt"));
        summary.putIfAbsent("lastSourceId", device.get("lastSourceId"));
        summary.putIfAbsent("lastDeviceId", device.get("lastDeviceId"));
        return summary;
    }

    private Map<String, Object> cashSummary(String tenantId, String period, String store) {
        Filter filter = cashFilter(tenantId, period, store);
        Filter movementFilter = scopeFilter(tenantId, store, "cm");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("openSessions", number("SELECT COUNT(*) FROM cash_sessions cs WHERE (cs.is_open = TRUE OR UPPER(COALESCE(cs.status, '')) IN ('OPEN', 'ABERTO')) AND cs.closed_at IS NULL AND " + filter.sql(), filter.args()));
        response.put("closedSessions", number("SELECT COUNT(*) FROM cash_sessions cs WHERE (cs.is_open = FALSE OR cs.closed_at IS NOT NULL OR UPPER(COALESCE(cs.status, '')) IN ('CLOSED', 'FECHADO')) AND " + filter.sql(), filter.args()));
        response.put("totalWithdrawals", currency(money("SELECT COALESCE(SUM(value), 0) FROM cash_movements cm WHERE cm.type IN ('SANGRIA', 'WITHDRAWAL') AND " + movementFilter.sql(), movementFilter.args())));
        response.put("totalSupplies", currency(money("SELECT COALESCE(SUM(value), 0) FROM cash_movements cm WHERE cm.type IN ('SUPRIMENTO', 'SUPPLY') AND " + movementFilter.sql(), movementFilter.args())));
        return response;
    }

    private Map<String, Object> financeSummary(String tenantId, String period, String store) {
        Filter filter = salesFilter(tenantId, period, store);
        Filter entryFilter = financialEntryFilter(tenantId, period, store);
        BigDecimal gross = money("SELECT COALESCE(SUM(si.quantity * si.unit_price), 0) FROM sale_items si INNER JOIN sales s ON s.tenant_id = si.tenant_id AND s.store_id = si.store_id AND s.id = si.sale_id WHERE s.status = 'PAID' AND " + filter.sql(), filter.args());
        BigDecimal discounts = money("SELECT COALESCE(SUM(si.discount), 0) FROM sale_items si INNER JOIN sales s ON s.tenant_id = si.tenant_id AND s.store_id = si.store_id AND s.id = si.sale_id WHERE s.status = 'PAID' AND " + filter.sql(), filter.args());
        BigDecimal surcharges = money("SELECT COALESCE(SUM(s.surcharge), 0) FROM sales s WHERE s.status = 'PAID' AND " + filter.sql(), filter.args());
        BigDecimal net = salesTotal(filter);
        BigDecimal cost = estimatedCost(filter);
        BigDecimal manualRevenue = financialEntryTotal(entryFilter, "RECEITA", "PAGO");
        BigDecimal manualExpenses = financialEntryTotal(entryFilter, "DESPESA", "PAGO");
        BigDecimal operationalBalance = net.add(manualRevenue).subtract(manualExpenses);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("grossRevenue", currency(gross));
        response.put("discounts", currency(discounts));
        response.put("surcharges", currency(surcharges));
        response.put("manualRevenue", currency(manualRevenue));
        response.put("manualExpenses", currency(manualExpenses));
        response.put("productCost", currency(cost));
        response.put("profitEstimate", currency(net.add(manualRevenue).subtract(manualExpenses).subtract(cost)));
        response.put("operationalBalance", currency(operationalBalance));
        return response;
    }

    private BigDecimal estimatedProfit(Filter salesFilter) {
        return salesTotal(salesFilter).subtract(estimatedCost(salesFilter));
    }

    private BigDecimal financialEntryTotal(Filter filter, String type, String status) {
        List<Object> args = new ArrayList<>(filter.args());
        args.add(type);
        args.add(status);
        return money("""
                SELECT COALESCE(SUM(fe.amount), 0)
                FROM financial_entries fe
                WHERE %s AND fe.type = ? AND fe.status = ?
                """.formatted(filter.sql()), args);
    }

    private BigDecimal estimatedCost(Filter salesFilter) {
        return money("""
                SELECT COALESCE(SUM(si.quantity * COALESCE(p.cost_price, 0)), 0)
                FROM sale_items si
                INNER JOIN sales s ON s.tenant_id = si.tenant_id AND s.store_id = si.store_id AND s.id = si.sale_id
                LEFT JOIN products p ON p.tenant_id = si.tenant_id AND p.store_id = si.store_id AND p.code = si.product_code
                WHERE s.status = 'PAID' AND %s
                """.formatted(salesFilter.sql()), salesFilter.args());
    }

    private List<Map<String, Object>> availableReports() {
        return List.of(
                Map.of("type", "sales", "title", "Vendas", "formats", List.of("JSON", "CSV", "PDF")),
                Map.of("type", "products", "title", "Produtos", "formats", List.of("JSON", "CSV")),
                Map.of("type", "stock", "title", "Estoque", "formats", List.of("JSON", "CSV")),
                Map.of("type", "cash", "title", "Caixa", "formats", List.of("JSON", "CSV")),
                Map.of("type", "finance", "title", "Financeiro", "formats", List.of("JSON", "CSV")),
                Map.of("type", "audit", "title", "Auditoria", "formats", List.of("JSON", "CSV"))
        );
    }

    private Map<String, Object> metric(String label, String value, String trend, String tone) {
        return Map.of("label", label, "value", value, "trend", trend, "tone", tone);
    }

    private Map<String, Object> chartRow(String label, BigDecimal value) {
        BigDecimal safeValue = safeMoney(value);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label == null || label.isBlank() ? "Sem data" : label);
        row.put("value", safeValue);
        row.put("display", currency(safeValue));
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

    private Map<String, Object> chartStatusRow(String label, Object value, String tone) {
        long safeValue = value instanceof Number number ? number.longValue() : 0L;
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("label", label);
        row.put("value", safeValue);
        row.put("display", String.valueOf(safeValue));
        row.put("tone", tone);
        return row;
    }

    private Filter salesFilter(String tenantId, String period, String store) {
        String normalizedStore = normalizeStore(store);
        Filter periodFilter = periodCondition("s.date_time", period, periodAnchor(period, "sales", "date_time", tenantId, normalizedStore));
        return scopeFilter(tenantId, normalizedStore, "s")
                .and(periodFilter.sql(), periodFilter.argsArray());
    }

    private Filter cashFilter(String tenantId, String period, String store) {
        String normalizedStore = normalizeStore(store);
        Filter periodFilter = periodCondition("COALESCE(cs.opened_at, cs.closed_at)", period, periodAnchor(period, "cash_sessions", "COALESCE(opened_at, closed_at)", tenantId, normalizedStore));
        return scopeFilter(tenantId, normalizedStore, "cs")
                .and(periodFilter.sql(), periodFilter.argsArray());
    }

    private Filter financialEntryFilter(String tenantId, String period, String store) {
        String normalizedStore = normalizeStore(store);
        Filter periodFilter = periodCondition("fe.entry_date", period, periodAnchor(period, "financial_entries", "entry_date", tenantId, normalizedStore));
        return scopeFilter(tenantId, normalizedStore, "fe")
                .and(periodFilter.sql(), periodFilter.argsArray());
    }

    private Filter auditFilter(String tenantId, String period, String store) {
        String normalizedStore = normalizeStore(store);
        Filter periodFilter = periodCondition("a.created_at", period, periodAnchor(period, "audit_log", "created_at", tenantId, normalizedStore));
        return scopeFilter(tenantId, normalizedStore, "a")
                .and(periodFilter.sql(), periodFilter.argsArray());
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

    private String column(String alias, String column) {
        return alias == null || alias.isBlank() ? column : alias + "." + column;
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
            case "month", "mes", "30d", "30dias", "30 dias" -> "month";
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

    private Map<String, Object> company(String tenantId, String store) {
        Map<String, Object> tenant = tenant(tenantId);
        Map<String, Object> activeStore = activeStore(tenantId, store);
        Map<String, Object> company = new LinkedHashMap<>();
        company.put("id", tenant.get("id"));
        company.put("name", tenant.get("name"));
        company.put("scope", activeStore.get("label"));
        return company;
    }

    private Map<String, Object> tenant(String tenantId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id, name, document, status
                FROM tenants
                WHERE id = ?
                LIMIT 1
                """, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getString("id"));
            row.put("name", rs.getString("name"));
            row.put("document", rs.getString("document"));
            row.put("status", rs.getString("status"));
            return row;
        }, tenantId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("id", tenantId);
        fallback.put("name", tenantId.equals("legacy") ? "Cliente legado" : tenantId);
        fallback.put("document", null);
        fallback.put("status", "ACTIVE");
        return fallback;
    }

    private Map<String, Object> activeStore(String tenantId, String store) {
        String normalized = normalizeStore(store);
        Map<String, Object> row = new LinkedHashMap<>();
        if (normalized == null) {
            row.put("id", "all");
            row.put("name", "Geral");
            row.put("label", "Todas as lojas");
            row.put("isAll", true);
            return row;
        }
        List<Map<String, Object>> stores = jdbcTemplate.query("""
                SELECT id, name, source_id
                FROM tenant_stores
                WHERE tenant_id = ? AND id = ?
                LIMIT 1
                """, (rs, rowNum) -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rs.getString("id"));
            item.put("name", storeDisplayName(rs.getString("name"), rs.getString("id")));
            item.put("label", rs.getString("source_id") == null ? rs.getString("id") : rs.getString("source_id"));
            item.put("sourceId", rs.getString("source_id"));
            item.put("isAll", false);
            return item;
        }, tenantId, normalized);
        if (!stores.isEmpty()) {
            return stores.get(0);
        }
        row.put("id", normalized);
        row.put("name", storeDisplayName(normalized, normalized));
        row.put("label", normalized);
        row.put("isAll", false);
        return row;
    }

    private BigDecimal money(String sql, List<Object> args) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args.toArray());
        return value == null ? BigDecimal.ZERO : value;
    }

    private Long number(String sql, List<Object> args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args.toArray());
        return value == null ? 0L : value;
    }

    private Object[] pagedArgs(Filter filter, int limit, int offset) {
        List<Object> args = new ArrayList<>(filter.args());
        args.add(limit);
        args.add(offset);
        return args.toArray();
    }

    private int safeLimit(int requested, int fallback, int max) {
        int value = requested <= 0 ? fallback : requested;
        return Math.max(1, Math.min(value, max));
    }

    private int safeOffset(int requested) {
        return Math.max(0, requested);
    }

    private String lastSync(String tenantId, String store) {
        Filter filter = scopeFilter(tenantId, store, null);
        List<String> result = jdbcTemplate.query("""
                SELECT CAST(received_at AS CHAR)
                FROM sync_runs
                WHERE status = 'SUCCESS' AND %s
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """.formatted(filter.sql()), (rs, rowNum) -> rs.getString(1), filter.argsArray());
        return result.isEmpty() ? null : result.get(0);
    }

    private String lastSourceId(String tenantId, String store) {
        Filter filter = scopeFilter(tenantId, store, null);
        List<String> result = jdbcTemplate.query("""
                SELECT source_id
                FROM sync_runs
                WHERE status = 'SUCCESS' AND %s
                ORDER BY received_at DESC, id DESC
                LIMIT 1
                """.formatted(filter.sql()), (rs, rowNum) -> rs.getString(1), filter.argsArray());
        return result.isEmpty() ? null : result.get(0);
    }

    private Object[] withFirstArg(Object first, Object[] rest) {
        Object[] args = new Object[(rest == null ? 0 : rest.length) + 1];
        args[0] = first;
        if (rest != null && rest.length > 0) {
            System.arraycopy(rest, 0, args, 1, rest.length);
        }
        return args;
    }

    private String currency(BigDecimal value) {
        return NumberFormat.getCurrencyInstance(PT_BR)
                .format(value == null ? BigDecimal.ZERO : value)
                .replace('\u00A0', ' ');
    }

    private String humanBytes(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB"};
        double value = bytes;
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(PT_BR, "%.1f %s", value, units[unit]);
    }

    private String backupIntegrityLabel(String status, boolean fileExists, boolean checksumValid) {
        if (!"CONCLUIDO".equalsIgnoreCase(status)) {
            return "Aguardando conclusão";
        }
        if (!fileExists) {
            return "Arquivo ausente";
        }
        return checksumValid ? "Íntegro" : "Inválido";
    }

    private String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException | NoSuchAlgorithmException e) {
            return "";
        }
    }

    static List<Map<String, Object>> deduplicateStores(List<Map<String, Object>> stores) {
        List<Map<String, Object>> deduplicated = new ArrayList<>();
        Set<String> seenSources = new LinkedHashSet<>();
        for (Map<String, Object> storeRow : stores) {
            String key = storeDedupKey(storeRow);
            if (seenSources.add(key)) {
                deduplicated.add(storeRow);
            }
        }
        return deduplicated;
    }

    private static String storeDedupKey(Map<String, Object> storeRow) {
        Object sourceId = storeRow.get("sourceId");
        Object storeId = storeRow.get("id");
        String raw = sourceId == null || sourceId.toString().isBlank() ? String.valueOf(storeId) : sourceId.toString();
        return raw == null ? "" : raw.trim().toLowerCase(PT_BR);
    }

    private String storeDisplayName(String preferredName, String fallback) {
        String raw = preferredName == null || preferredName.isBlank() ? fallback : preferredName;
        if (raw == null || raw.isBlank()) {
            return "Loja sem origem";
        }
        String normalized = raw.trim()
                .replace('_', ' ')
                .replace('-', ' ')
                .replaceAll("\\s+", " ")
                .toLowerCase(PT_BR);
        StringBuilder builder = new StringBuilder();
        for (String part : normalized.split(" ")) {
            if (part.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append(' ');
            }
            builder.append(part.substring(0, 1).toUpperCase(PT_BR)).append(part.substring(1));
        }
        return builder.isEmpty() ? raw : builder.toString();
    }

    private String paymentName(String value) {
        if (value == null) {
            return "Não informado";
        }
        return switch (value.toUpperCase()) {
            case "CASH" -> "Dinheiro";
            case "CARD" -> "Cartão";
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
            case "PAID" -> "Concluída";
            case "CANCELLED" -> "Cancelada";
            case "OPEN" -> "Aberta";
            default -> value;
        };
    }

    private record Filter(String sql, List<Object> args) {
        private Filter and(String condition) {
            return new Filter("(" + sql + ") AND (" + condition + ")", args);
        }

        private Filter and(String condition, Object... extraArgs) {
            List<Object> copy = new ArrayList<>(args);
            if (extraArgs != null) {
                copy.addAll(List.of(extraArgs));
            }
            return new Filter("(" + sql + ") AND (" + condition + ")", copy);
        }

        private Object[] argsArray() {
            return args.toArray();
        }
    }
}
