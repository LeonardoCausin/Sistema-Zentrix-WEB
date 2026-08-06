package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.BillingService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    @PostMapping("/checkout")
    public Map<String, Object> checkout() {
        return billingService.checkout(AuthContext.tenantId());
    }
}
