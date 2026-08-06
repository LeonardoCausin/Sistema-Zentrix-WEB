package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.BillingService;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/webhooks/asaas")
public class AsaasWebhookController {
    private final BillingService billingService;

    public AsaasWebhookController(BillingService billingService) {
        this.billingService = billingService;
    }

    @PostMapping
    public Map<String, Object> receive(
            @RequestHeader(name = "asaas-access-token", required = false) String webhookToken,
            @RequestBody Map<String, Object> payload
    ) {
        return billingService.processAsaasWebhook(webhookToken, payload);
    }
}
