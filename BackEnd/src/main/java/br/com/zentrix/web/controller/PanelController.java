package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.WebDataService;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.PermissionService;
import br.com.zentrix.web.service.PermissionService.Permission;
import br.com.zentrix.web.service.ReportService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PanelController {

    private final WebDataService dataService;
    private final ReportService reportService;
    private final PermissionService permissionService;

    public PanelController(WebDataService dataService, ReportService reportService, PermissionService permissionService) {
        this.dataService = dataService;
        this.reportService = reportService;
        this.permissionService = permissionService;
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
            @RequestParam(defaultValue = "all") String store
    ) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.sales(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.products(AuthContext.tenantId(), store);
    }

    @GetMapping("/cash-sessions")
    public List<Map<String, Object>> cashSessions(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "all") String store
    ) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.cashSessions(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/stock/alerts")
    public List<Map<String, Object>> stockAlerts(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.stockAlerts(AuthContext.tenantId(), store);
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "all") String store
    ) {
        permissionService.require(Permission.VIEW_REPORTS);
        return dataService.auditEvents(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/backups")
    public List<Map<String, Object>> backups(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.MANAGE_SETTINGS);
        return dataService.backups(AuthContext.tenantId(), store);
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> clients(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.clients(AuthContext.tenantId(), store);
    }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.MANAGE_USERS);
        return dataService.employees(AuthContext.tenantId(), store);
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

    @GetMapping("/stores")
    public List<Map<String, Object>> stores() {
        permissionService.require(Permission.VIEW_PANEL);
        return dataService.stores(AuthContext.tenantId());
    }
}
