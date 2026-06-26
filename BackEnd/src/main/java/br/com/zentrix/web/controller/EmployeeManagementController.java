package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.EmployeeRequest;
import br.com.zentrix.web.dto.PermissionUpdateRequest;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.BusinessOperationsService;
import jakarta.validation.Valid;
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
@RequestMapping("/api/employees")
public class EmployeeManagementController {
    private final BusinessOperationsService operationsService;

    public EmployeeManagementController(BusinessOperationsService operationsService) {
        this.operationsService = operationsService;
    }

    @GetMapping("/{username}")
    public Map<String, Object> detail(@PathVariable String username) {
        return operationsService.employee(AuthContext.tenantId(), username);
    }

    @PostMapping
    public Map<String, Object> create(@RequestParam(defaultValue = "WEB") String store, @Valid @RequestBody EmployeeRequest request) {
        return operationsService.createEmployee(AuthContext.tenantId(), store, request);
    }

    @PutMapping("/{username}")
    public Map<String, Object> update(@PathVariable String username, @Valid @RequestBody EmployeeRequest request) {
        return operationsService.updateEmployee(AuthContext.tenantId(), username, request);
    }

    @PatchMapping("/{username}/status")
    public Map<String, Object> status(@PathVariable String username, @RequestParam boolean active) {
        return operationsService.updateEmployeeStatus(AuthContext.tenantId(), username, active);
    }

    @PutMapping("/{username}/permissions")
    public Map<String, Object> permissions(@PathVariable String username, @Valid @RequestBody PermissionUpdateRequest request) {
        return operationsService.updatePermissions(AuthContext.tenantId(), username, request);
    }
}
