package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.AlertService;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.BusinessOperationsService;
import br.com.zentrix.web.service.LicenseService;
import br.com.zentrix.web.service.PermissionService;
import br.com.zentrix.web.service.PermissionService.Permission;
import br.com.zentrix.web.service.ReportService;
import br.com.zentrix.web.service.WebDataService;
import java.net.MalformedURLException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AppGestaoController {
    private final AlertService alertService;
    private final LicenseService licenseService;
    private final WebDataService webDataService;
    private final BusinessOperationsService operationsService;
    private final ReportService reportService;
    private final PermissionService permissionService;

    public AppGestaoController(
            AlertService alertService,
            LicenseService licenseService,
            WebDataService webDataService,
            BusinessOperationsService operationsService,
            ReportService reportService,
            PermissionService permissionService
    ) {
        this.alertService = alertService;
        this.licenseService = licenseService;
        this.webDataService = webDataService;
        this.operationsService = operationsService;
        this.reportService = reportService;
        this.permissionService = permissionService;
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_PANEL);
        return alertService.alerts(AuthContext.tenantId(), store);
    }

    @GetMapping("/license")
    public Map<String, Object> license() {
        permissionService.require(Permission.MANAGE_LICENSE);
        return licenseService.current(AuthContext.tenantId());
    }

    @GetMapping("/devices")
    public List<Map<String, Object>> devices() {
        permissionService.require(Permission.MANAGE_LICENSE);
        return licenseService.devices(AuthContext.tenantId());
    }

    @GetMapping("/reports/sales")
    public Object salesReport(@RequestParam(defaultValue = "today") String period, @RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_REPORTS);
        return reportService.sales(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/reports/products")
    public Object productsReport(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_REPORTS);
        return reportService.products(AuthContext.tenantId(), store);
    }

    @GetMapping("/reports/stock")
    public Object stockReport(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_REPORTS);
        return reportService.stock(AuthContext.tenantId(), store);
    }

    @GetMapping("/reports/cash")
    public Object cashReport(@RequestParam(defaultValue = "today") String period, @RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_REPORTS);
        return reportService.cash(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/reports/finance")
    public Object financeReport(@RequestParam(defaultValue = "today") String period, @RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_REPORTS);
        return reportService.finance(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/reports/audit")
    public Object auditReport(@RequestParam(defaultValue = "today") String period, @RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_REPORTS);
        return reportService.audit(AuthContext.tenantId(), period, store);
    }

    @PostMapping("/backups/manual")
    public Map<String, Object> manualBackup(@RequestParam(defaultValue = "WEB") String store) {
        return operationsService.manualBackup(AuthContext.tenantId(), store);
    }

    @PostMapping("/backups/{id}/restore")
    public Map<String, Object> restore(@PathVariable long id, @RequestBody(required = false) Map<String, Object> request) {
        return operationsService.restoreBackup(AuthContext.tenantId(), id, request);
    }

    @PostMapping("/backups/restore-staging/{stagingId}/apply")
    public Map<String, Object> applyRestore(
            @PathVariable long stagingId,
            @RequestBody(required = false) Map<String, Object> request
    ) {
        return operationsService.applyStagedRestore(AuthContext.tenantId(), stagingId, request);
    }

    @GetMapping("/backups/{id}/restore/preview")
    public Map<String, Object> restorePreview(@PathVariable long id) {
        return operationsService.restoreBackupPreview(AuthContext.tenantId(), id);
    }

    @GetMapping("/backups/{id}/download")
    public ResponseEntity<Resource> downloadBackup(@PathVariable long id) throws MalformedURLException {
        Path file = operationsService.validatedBackupFile(AuthContext.tenantId(), id);
        String fileName = operationsService.backupDownloadName(AuthContext.tenantId(), id);
        Resource resource = new UrlResource(file.toUri());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CONTENT_TYPE, "application/sql; charset=utf-8")
                .body(resource);
    }
}
