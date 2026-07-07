package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.WebDataService;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.AuditService;
import br.com.zentrix.web.service.PermissionService;
import br.com.zentrix.web.service.PermissionService.Permission;
import br.com.zentrix.web.service.ReportService;
import br.com.zentrix.web.service.SettingsService;
import br.com.zentrix.web.service.WebChangeOutboxService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PanelController {

    private final WebDataService dataService;
    private final ReportService reportService;
    private final PermissionService permissionService;
    private final WebChangeOutboxService webChangeOutboxService;
    private final SettingsService settingsService;
    private final AuditService auditService;

    public PanelController(
            WebDataService dataService,
            ReportService reportService,
            PermissionService permissionService,
            WebChangeOutboxService webChangeOutboxService,
            SettingsService settingsService,
            AuditService auditService
    ) {
        this.dataService = dataService;
        this.reportService = reportService;
        this.permissionService = permissionService;
        this.webChangeOutboxService = webChangeOutboxService;
        this.settingsService = settingsService;
        this.auditService = auditService;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "all") String store
    ) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.dashboard(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/sales")
    public List<Map<String, Object>> sales(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.sales(AuthContext.tenantId(), period, store, limit, offset);
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products(
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.products(AuthContext.tenantId(), store, limit, offset);
    }

    @GetMapping("/cash-sessions")
    public List<Map<String, Object>> cashSessions(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.cashSessions(AuthContext.tenantId(), period, store, limit, offset);
    }

    @GetMapping("/stock/alerts")
    public List<Map<String, Object>> stockAlerts(
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.stockAlerts(AuthContext.tenantId(), store, limit, offset);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(defaultValue = "50") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        permissionService.require(Permission.VIEW_REPORTS);
        return dataService.auditEvents(AuthContext.tenantId(), period, store, limit, offset);
    }

    @GetMapping("/backups")
    public List<Map<String, Object>> backups(
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        permissionService.require(Permission.MANAGE_SETTINGS);
        return dataService.backups(AuthContext.tenantId(), store, limit, offset);
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> clients(
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.clients(AuthContext.tenantId(), store, limit, offset);
    }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees(
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        permissionService.require(Permission.MANAGE_USERS);
        return dataService.employees(AuthContext.tenantId(), store, limit, offset);
    }

    @GetMapping("/finance")
    public Map<String, Object> finance(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "all") String store
    ) {
        permissionService.require(Permission.MANAGE_FINANCE);
        return dataService.finance(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/reports")
    public Map<String, Object> reports(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "all") String store
    ) {
        permissionService.require(Permission.VIEW_REPORTS);
        return reportService.overview(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.MANAGE_SETTINGS);
        return dataService.settings(AuthContext.tenantId(), store);
    }

    @PutMapping("/settings")
    public Map<String, Object> updateSettings(
            @RequestParam(defaultValue = "all") String store,
            @RequestBody Map<String, Object> request
    ) {
        permissionService.require(Permission.MANAGE_SETTINGS);
        Map<String, Object> updated = settingsService.updateSettings(AuthContext.tenantId(), store, request);
        auditService.recordCurrent("SETTINGS_UPDATED", "app_settings", store, "Configurações do sistema atualizadas.", "ALERTA", reason(request));
        return Map.of("settings", updated, "store", store);
    }

    @GetMapping("/stores")
    public List<Map<String, Object>> stores() {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.stores(AuthContext.tenantId());
    }

    @GetMapping("/sync/monitor")
    public Map<String, Object> syncMonitor(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_REPORTS);
        return webChangeOutboxService.monitor(AuthContext.tenantId(), store);
    }

    @PostMapping("/sync/outbox/{id}/retry")
    public Map<String, Object> retryOutbox(
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String store,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        permissionService.require(Permission.VIEW_REPORTS);
        return webChangeOutboxService.retryOutboxItem(AuthContext.tenantId(), store, id, reason(request));
    }

    @PostMapping("/sync/outbox/{id}/dead-letter")
    public Map<String, Object> deadLetterOutbox(
            @PathVariable long id,
            @RequestParam(defaultValue = "all") String store,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        permissionService.require(Permission.VIEW_REPORTS);
        return webChangeOutboxService.deadLetterOutboxItem(AuthContext.tenantId(), store, id, reason(request));
    }

    private String reason(Map<String, Object> request) {
        Object value = request == null ? null : request.get("reason");
        return value == null ? null : String.valueOf(value);
    }
}
