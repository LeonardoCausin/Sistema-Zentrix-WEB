package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.AlertService;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.BusinessOperationsService;
import br.com.zentrix.web.service.LicenseService;
import br.com.zentrix.web.service.ReportService;
import br.com.zentrix.web.service.WebDataService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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

    public AppGestaoController(
            AlertService alertService,
            LicenseService licenseService,
            WebDataService webDataService,
            BusinessOperationsService operationsService,
            ReportService reportService
    ) {
        this.alertService = alertService;
        this.licenseService = licenseService;
        this.webDataService = webDataService;
        this.operationsService = operationsService;
        this.reportService = reportService;
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts(@RequestParam(defaultValue = "all") String store) {
        return alertService.alerts(AuthContext.tenantId(), store);
    }

    @GetMapping("/license")
    public Map<String, Object> license() {
        return licenseService.current(AuthContext.tenantId());
    }

    @GetMapping("/devices")
    public List<Map<String, Object>> devices() {
        return licenseService.devices(AuthContext.tenantId());
    }

    @GetMapping("/reports/sales")
    public Object salesReport(@RequestParam(defaultValue = "today") String period, @RequestParam(defaultValue = "all") String store) {
        return reportService.sales(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/reports/products")
    public Object productsReport(@RequestParam(defaultValue = "all") String store) {
        return reportService.products(AuthContext.tenantId(), store);
    }

    @GetMapping("/reports/stock")
    public Object stockReport(@RequestParam(defaultValue = "all") String store) {
        return reportService.stock(AuthContext.tenantId(), store);
    }

    @GetMapping("/reports/cash")
    public Object cashReport(@RequestParam(defaultValue = "today") String period, @RequestParam(defaultValue = "all") String store) {
        return reportService.cash(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/reports/finance")
    public Object financeReport(@RequestParam(defaultValue = "today") String period, @RequestParam(defaultValue = "all") String store) {
        return reportService.finance(AuthContext.tenantId(), period, store);
    }

    @GetMapping("/reports/audit")
    public Object auditReport(@RequestParam(defaultValue = "today") String period, @RequestParam(defaultValue = "all") String store) {
        return reportService.audit(AuthContext.tenantId(), period, store);
    }

    @PostMapping("/backups/manual")
    public Map<String, Object> manualBackup(@RequestParam(defaultValue = "WEB") String store) {
        return operationsService.manualBackup(AuthContext.tenantId(), store);
    }

    @PostMapping("/backups/{id}/restore")
    public Map<String, Object> restore(@PathVariable long id) {
        return operationsService.restoreBackup(id);
    }
}
