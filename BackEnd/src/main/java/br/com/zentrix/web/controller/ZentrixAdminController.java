package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.LocalAdminAccessService;
import br.com.zentrix.web.service.PermissionService;
import br.com.zentrix.web.service.PermissionService.Permission;
import br.com.zentrix.web.service.ZentrixAdminService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/zentrix-admin")
public class ZentrixAdminController {
    private final LocalAdminAccessService accessService;
    private final PermissionService permissionService;
    private final ZentrixAdminService zentrixAdminService;

    public ZentrixAdminController(
            LocalAdminAccessService accessService,
            PermissionService permissionService,
            ZentrixAdminService zentrixAdminService
    ) {
        this.accessService = accessService;
        this.permissionService = permissionService;
        this.zentrixAdminService = zentrixAdminService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        requireAccess(request);
        return zentrixAdminService.overview();
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> clients(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request
    ) {
        requireAccess(request);
        return zentrixAdminService.clients(search, status, limit);
    }

    @PostMapping("/clients")
    public Map<String, Object> createClient(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireAccess(request);
        return zentrixAdminService.createClient(body);
    }

    @GetMapping("/clients/{tenantId}")
    public Map<String, Object> client(@PathVariable String tenantId, HttpServletRequest request) {
        requireAccess(request);
        return zentrixAdminService.client(tenantId);
    }

    @PutMapping("/clients/{tenantId}/status")
    public Map<String, Object> updateClientStatus(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireAccess(request);
        return zentrixAdminService.updateClientStatus(tenantId, body);
    }

    @PostMapping("/clients/{tenantId}/licenses")
    public Map<String, Object> createLicense(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireAccess(request);
        return zentrixAdminService.createLicense(tenantId, body);
    }

    @PostMapping("/clients/{tenantId}/activation-codes")
    public Map<String, Object> createActivationCode(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireAccess(request);
        return zentrixAdminService.createActivationCode(tenantId, body);
    }

    private void requireAccess(HttpServletRequest request) {
        accessService.requireLocal(request);
        permissionService.require(Permission.MANAGE_LICENSE);
    }
}
