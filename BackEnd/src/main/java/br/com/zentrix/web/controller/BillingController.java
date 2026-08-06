package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.BillingService;
import java.util.Map;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final BillingService billingService;

    public BillingController(BillingService billingService) {
        this.billingService = billingService;
    }

    @GetMapping("/current")
    public Map<String, Object> current() {
        return billingService.current(AuthContext.tenantId());
    }

    @GetMapping("/portal")
    public Map<String, Object> portal() {
        return billingService.portal(AuthContext.tenantId());
    }

    @GetMapping("/invoices")
    public List<Map<String, Object>> invoices(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "24") int limit
    ) {
        return billingService.invoices(AuthContext.tenantId(), status, limit);
    }

    @GetMapping("/plan-preview")
    public Map<String, Object> planPreview(@RequestParam String plan) {
        return billingService.previewPlanChange(AuthContext.tenantId(), plan);
    }

    @PostMapping("/notifications/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markNotificationRead(@PathVariable long id) {
        billingService.markNotificationRead(AuthContext.tenantId(), id);
    }

    @PostMapping("/checkout")
    public Map<String, Object> checkout() {
        return billingService.checkout(AuthContext.tenantId());
    }
}
