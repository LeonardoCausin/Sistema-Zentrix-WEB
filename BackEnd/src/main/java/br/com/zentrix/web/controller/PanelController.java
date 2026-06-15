package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.WebDataService;
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

    public PanelController(WebDataService dataService) {
        this.dataService = dataService;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard(@RequestParam(defaultValue = "today") String period) {
        return dataService.dashboard(period);
    }

    @GetMapping("/sales")
    public List<Map<String, Object>> sales(@RequestParam(defaultValue = "today") String period) {
        return dataService.sales(period);
    }

    @GetMapping("/products")
    public List<Map<String, Object>> products() {
        return dataService.products();
    }

    @GetMapping("/cash-sessions")
    public List<Map<String, Object>> cashSessions(@RequestParam(defaultValue = "today") String period) {
        return dataService.cashSessions(period);
    }

    @GetMapping("/stock/alerts")
    public List<Map<String, Object>> stockAlerts() {
        return dataService.stockAlerts();
    }

    @GetMapping("/audit")
    public List<Map<String, Object>> audit(@RequestParam(defaultValue = "today") String period) {
        return dataService.auditEvents(period);
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
    public Map<String, Object> finance(@RequestParam(defaultValue = "today") String period) {
        return dataService.finance(period);
    }

    @GetMapping("/reports")
    public Map<String, Object> reports(@RequestParam(defaultValue = "today") String period) {
        return dataService.reports(period);
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return dataService.settings();
    }
}
