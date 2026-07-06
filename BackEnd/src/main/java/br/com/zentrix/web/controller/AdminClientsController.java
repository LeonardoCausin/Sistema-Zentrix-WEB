package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.ClientRequest;
import br.com.zentrix.web.dto.ClientStatusRequest;
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
@RequestMapping({"/api/admin/clientes", "/api/admin/clients"})
public class AdminClientsController {
    private final BusinessOperationsService operationsService;

    public AdminClientsController(BusinessOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping
    public List<Map<String, Object>> list(
            @RequestParam(defaultValue = "all") String store,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        return operationsService.adminClients(AuthContext.tenantId(), store, search, status, limit, offset);
    }

    @GetMapping("/{id}")
    public Map<String, Object> detail(
            @PathVariable int id,
            @RequestParam(defaultValue = "WEB") String store
    ) {
        return operationsService.adminClient(AuthContext.tenantId(), store, id);
    }

    @PostMapping
    public Map<String, Object> create(
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody ClientRequest request
    ) {
        return operationsService.createClient(AuthContext.tenantId(), store, request);
    }

    @PutMapping("/{id}")
    public Map<String, Object> update(
            @PathVariable int id,
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody ClientRequest request
    ) {
        return operationsService.updateClient(AuthContext.tenantId(), store, id, request);
    }

    @PatchMapping("/{id}/status")
    public Map<String, Object> status(
            @PathVariable int id,
            @RequestParam(defaultValue = "WEB") String store,
            @Valid @RequestBody ClientStatusRequest request
    ) {
        return operationsService.updateClientStatus(AuthContext.tenantId(), store, id, request.active(), request.reason());
    }
}
