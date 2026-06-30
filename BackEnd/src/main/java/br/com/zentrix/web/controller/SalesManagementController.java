package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.CancelSaleRequest;
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
@RequestMapping("/api/sales")
public class SalesManagementController {
    private final BusinessOperationsService operationsService;
    private final PermissionService permissionService;

    public SalesManagementController(BusinessOperationsService operationsService, PermissionService permissionService) {
        this.operationsService = operationsService;
        this.permissionService = permissionService;
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(@PathVariable int id, @RequestParam(defaultValue = "WEB") String store) {
        permissionService.require(Permission.VIEW_PANEL);
        return operationsService.saleDetail(AuthContext.tenantId(), store, id);
    }

    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(
            @PathVariable int id,
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody CancelSaleRequest request
    ) {
        return operationsService.cancelSale(AuthContext.tenantId(), store, id, request);
    }
}
