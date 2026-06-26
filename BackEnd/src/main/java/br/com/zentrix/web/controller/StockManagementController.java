package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.StockAdjustmentRequest;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.BusinessOperationsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/stock")
public class StockManagementController {
    private final BusinessOperationsService operationsService;

    public StockManagementController(BusinessOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/movements")
    public List<Map<String, Object>> movements(@RequestParam(defaultValue = "all") String store) {
        return operationsService.stockMovements(AuthContext.tenantId(), store);
    }

    @PostMapping("/adjust")
    public Map<String, Object> adjust(@RequestParam(defaultValue = "WEB") String store, @Valid @RequestBody StockAdjustmentRequest request) {
        return operationsService.adjustStock(AuthContext.tenantId(), store, request, "AJUSTE");
    }

    @PostMapping("/entry")
    public Map<String, Object> entry(@RequestParam(defaultValue = "WEB") String store, @Valid @RequestBody StockAdjustmentRequest request) {
        return operationsService.adjustStock(AuthContext.tenantId(), store, request, "ENTRADA");
    }

    @PostMapping("/manual-output")
    public Map<String, Object> output(@RequestParam(defaultValue = "WEB") String store, @Valid @RequestBody StockAdjustmentRequest request) {
        return operationsService.adjustStock(AuthContext.tenantId(), store, request, "SAIDA_MANUAL");
    }
}
