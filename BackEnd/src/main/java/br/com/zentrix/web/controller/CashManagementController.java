package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.CashMovementRequest;
import br.com.zentrix.web.dto.CloseCashSessionRequest;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.BusinessOperationsService;
import br.com.zentrix.web.service.PermissionService;
import br.com.zentrix.web.service.PermissionService.Permission;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CashManagementController {
    private final BusinessOperationsService operationsService;
    private final PermissionService permissionService;

    public CashManagementController(BusinessOperationsService operationsService, PermissionService permissionService) {
        this.operationsService = operationsService;
        this.permissionService = permissionService;
    }

    @GetMapping("/cash/current")
    public Map<String, Object> current(@RequestParam(defaultValue = "all") String store) {
        permissionService.require(Permission.VIEW_PANEL);
        return operationsService.currentCash(AuthContext.tenantId(), store);
    }

    @GetMapping("/cash-sessions/{id}")
    public Map<String, Object> detail(@PathVariable int id, @RequestParam(defaultValue = "WEB") String store) {
        permissionService.require(Permission.VIEW_PANEL);
        return operationsService.cashSession(AuthContext.tenantId(), store, id);
    }

    @PostMapping("/cash-sessions/{id}/close")
    public Map<String, Object> close(@PathVariable int id, @RequestParam(defaultValue = "WEB") String store, @Valid @RequestBody CloseCashSessionRequest request) {
        return operationsService.closeCash(AuthContext.tenantId(), store, id, request);
    }

    @PostMapping("/cash-sessions/{id}/withdrawal")
    public Map<String, Object> withdrawal(@PathVariable int id, @RequestParam(defaultValue = "WEB") String store, @Valid @RequestBody CashMovementRequest request) {
        return operationsService.withdrawal(AuthContext.tenantId(), store, id, request);
    }

    @PostMapping("/cash-sessions/{id}/supply")
    public Map<String, Object> supply(@PathVariable int id, @RequestParam(defaultValue = "WEB") String store, @Valid @RequestBody CashMovementRequest request) {
        return operationsService.supply(AuthContext.tenantId(), store, id, request);
    }
}
