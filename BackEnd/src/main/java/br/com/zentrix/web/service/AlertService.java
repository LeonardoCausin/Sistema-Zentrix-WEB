package br.com.zentrix.web.service;

import java.time.LocalDateTime;
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
public class AlertService {
    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final LicenseService licenseService;

    public AlertService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer, LicenseService licenseService) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.licenseService = licenseService;
    }

    public List<Map<String, Object>> alerts(String tenantId, String storeId) {
        initializer.ensureReady();
        List<Map<String, Object>> alerts = new ArrayList<>();
        Number lowStock = count("SELECT COUNT(*) FROM products WHERE tenant_id = ? AND (? = 'all' OR store_id = ?) AND stock > 0 AND stock <= min_stock", tenantId, normalizeStore(storeId), normalizeStore(storeId));
        Number emptyStock = count("SELECT COUNT(*) FROM products WHERE tenant_id = ? AND (? = 'all' OR store_id = ?) AND stock <= 0", tenantId, normalizeStore(storeId), normalizeStore(storeId));
        Number openCash = count("SELECT COUNT(*) FROM cash_sessions WHERE tenant_id = ? AND (? = 'all' OR store_id = ?) AND closed_at IS NULL AND (is_open = TRUE OR UPPER(COALESCE(status, '')) IN ('OPEN', 'ABERTO')) AND COALESCE(opened_at, NOW()) < DATE_SUB(NOW(), INTERVAL 12 HOUR)", tenantId, normalizeStore(storeId), normalizeStore(storeId));
        Number cancelled = count("SELECT COUNT(*) FROM sales WHERE tenant_id = ? AND (? = 'all' OR store_id = ?) AND status = 'CANCELLED' AND date_time >= DATE_SUB(NOW(), INTERVAL 7 DAY)", tenantId, normalizeStore(storeId), normalizeStore(storeId));
        Number syncFailures = count("SELECT COUNT(*) FROM sync_runs WHERE tenant_id = ? AND (? = 'all' OR store_id = ?) AND status <> 'SUCCESS' AND received_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)", tenantId, normalizeStore(storeId), normalizeStore(storeId));

        if (emptyStock.intValue() > 0) {
            alerts.add(alert("stock_empty", "danger", "Estoque zerado", emptyStock + " produto(s) sem estoque.", "Ver estoque", "/estoque.html", tenantId, storeId));
        }
        if (lowStock.intValue() > 0) {
            alerts.add(alert("stock_low", "warning", "Estoque baixo", lowStock + " produto(s) abaixo do mínimo.", "Repor estoque", "/estoque.html", tenantId, storeId));
        }
        if (openCash.intValue() > 0) {
            alerts.add(alert("cash_open_long", "warning", "Caixa aberto há muito tempo", openCash + " caixa(s) abertos há mais de 12 horas.", "Ver caixa", "/caixa.html", tenantId, storeId));
        }
        if (cancelled.intValue() > 5) {
            alerts.add(alert("cancelled_sales", "warning", "Cancelamentos elevados", cancelled + " venda(s) canceladas nos últimos 7 dias.", "Ver vendas", "/vendas.html", tenantId, storeId));
        }
        if (syncFailures.intValue() > 0) {
            alerts.add(alert("sync_failures", "danger", "Falhas de sincronização", syncFailures + " falha(s) recentes de sincronização.", "Ver backups", "/backups.html", tenantId, storeId));
        }

        Map<String, Object> license = licenseService.current(tenantId);
        String status = String.valueOf(license.getOrDefault("status", "ACTIVE"));
        if ("EXPIRED".equalsIgnoreCase(status) || "BLOCKED".equalsIgnoreCase(status)) {
            alerts.add(alert("license_expired", "danger", "Licença vencida", "A licença precisa de atenção para manter o AppGestão ativo.", "Ver licença", "/configuracoes.html", tenantId, storeId));
        } else if ("NEAR_EXPIRATION".equalsIgnoreCase(status)) {
            alerts.add(alert("license_near_expiration", "warning", "Licença próxima do vencimento", "Renove para evitar bloqueios.", "Ver licença", "/configuracoes.html", tenantId, storeId));
        }

        if (alerts.isEmpty()) {
            alerts.add(alert("all_good", "info", "Operação sem alertas críticos", "Nenhum alerta importante no momento.", "Ver dashboard", "/dashboard.html", tenantId, storeId));
        }
        return alerts;
    }

    private Number count(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private Map<String, Object> alert(String type, String level, String title, String message, String actionLabel, String actionUrl, String tenantId, String storeId) {
        Map<String, Object> alert = new LinkedHashMap<>();
        alert.put("id", type + "-" + Math.abs((tenantId + ":" + storeId).hashCode()));
        alert.put("type", type);
        alert.put("level", level);
        alert.put("title", title);
        alert.put("message", message);
        alert.put("actionLabel", actionLabel);
        alert.put("actionUrl", actionUrl);
        alert.put("createdAt", LocalDateTime.now().toString());
        alert.put("tenantId", tenantId);
        alert.put("storeId", storeId);
        return alert;
    }

    private String normalizeStore(String storeId) {
        AuthTokenService.SessionToken session = AuthContext.current().orElse(null);
        if (storeId == null || storeId.isBlank() || "all".equalsIgnoreCase(storeId)) {
            return session == null || canAccessAllStores(session) ? "all" : session.storeId();
        }
        String store = storeId.trim();
        if (session != null && !canAccessAllStores(session) && !store.equals(session.storeId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Você não tem permissão para acessar esta loja.");
        }
        return store;
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
}
