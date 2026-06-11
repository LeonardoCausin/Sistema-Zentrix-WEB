package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.WebDataService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PanelController {

    private final WebDataService dataService;

    public PanelController(WebDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        return dataService.dashboard();
    }

    @GetMapping("/sales")
    public List<Map<String, Object>> sales() {
        return dataService.sales();
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products() {
        return dataService.products();
    }

    @GetMapping("/cash-sessions")
    public List<Map<String, Object>> cashSessions() {
        return dataService.cashSessions();
    }

    @GetMapping("/stock/alerts")
    public List<Map<String, Object>> stockAlerts() {
        return dataService.stockAlerts();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit() {
        return dataService.auditEvents();
    }

    @GetMapping("/backups")
    public List<Map<String, Object>> backups() {
        return dataService.backups();
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> clients() {
        return dataService.clients();
    }

    @GetMapping("/employees")
    public List<Map<String, Object>> employees() {
        return dataService.employees();
    }

    @GetMapping("/finance")
    public Map<String, Object> finance() {
        return dataService.finance();
    }

    @GetMapping("/reports")
    public Map<String, Object> reports() {
        return dataService.reports();
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return dataService.settings();
    }
}
