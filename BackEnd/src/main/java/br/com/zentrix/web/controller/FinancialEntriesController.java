package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.FinancialEntryRequest;
import br.com.zentrix.web.dto.FinancialEntryStatusRequest;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.BusinessOperationsService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/finance/entries")
public class FinancialEntriesController {
    private final BusinessOperationsService operationsService;

    public FinancialEntriesController(BusinessOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(defaultValue = "today") String period,
            @RequestParam(defaultValue = "all") String type,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return operationsService.financialEntries(AuthContext.tenantId(), store, period, type, status, limit, offset);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(
            @PathVariable int id,
            @RequestParam(defaultValue = "WEB") String store
    ) {
        return operationsService.financialEntry(AuthContext.tenantId(), store, id);
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody FinancialEntryRequest request
    ) {
        return operationsService.createFinancialEntry(AuthContext.tenantId(), store, request);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable int id,
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody FinancialEntryRequest request
    ) {
        return operationsService.updateFinancialEntry(AuthContext.tenantId(), store, id, request);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> status(
            @PathVariable int id,
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody FinancialEntryStatusRequest request
    ) {
        return operationsService.updateFinancialEntryStatus(AuthContext.tenantId(), store, id, request);
    }
}
