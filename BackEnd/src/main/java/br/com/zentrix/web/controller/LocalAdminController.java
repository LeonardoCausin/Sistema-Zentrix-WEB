package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.LocalAdminAccessService;
import br.com.zentrix.web.service.LocalAdminService;
import br.com.zentrix.web.service.PermissionService;
import br.com.zentrix.web.service.PermissionService.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/local-admin")
public class LocalAdminController {
    private final LocalAdminAccessService accessService;
    private final LocalAdminService localAdminService;
    private final PermissionService permissionService;

    public LocalAdminController(
            LocalAdminAccessService accessService,
            LocalAdminService localAdminService,
            PermissionService permissionService
    ) {
        this.accessService = accessService;
        this.localAdminService = localAdminService;
        this.permissionService = permissionService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(@RequestParam(defaultValue = "all") String store, HttpServletRequest request) {
        requireAccess(request);
        return localAdminService.overview(AuthContext.tenantId(), store);
    }

    @PostMapping("/cash/normalize-statuses")
    public Map<String, Object> normalizeCashStatuses(
            @RequestParam(defaultValue = "all") String store,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireAccess(request);
        return localAdminService.normalizeCashStatuses(AuthContext.tenantId(), store, body);
    }

    @PostMapping("/cash/{id}/close")
    public Map<String, Object> closeCash(
            @PathVariable long id,
            @RequestParam String store,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireAccess(request);
        return localAdminService.closeCash(AuthContext.tenantId(), store, id, body);
    }

    @DeleteMapping("/cash/{id}")
    public Map<String, Object> deleteCash(
            @PathVariable long id,
            @RequestParam String store,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireAccess(request);
        return localAdminService.deleteCash(AuthContext.tenantId(), store, id, body);
    }

    @PostMapping("/sync/clear-failures")
    public Map<String, Object> clearSyncFailures(
            @RequestParam(defaultValue = "all") String store,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireAccess(request);
        return localAdminService.clearSyncFailures(AuthContext.tenantId(), store, body);
    }

    @PostMapping("/backups/clear-errors")
    public Map<String, Object> clearBackupErrors(
            @RequestParam(defaultValue = "all") String store,
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireAccess(request);
        return localAdminService.clearBackupErrors(AuthContext.tenantId(), store, body);
    }

    @PostMapping("/cache/clear")
    public Map<String, Object> clearCache(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
        requireAccess(request);
        return localAdminService.clearCache(body);
    }

    private void requireAccess(HttpServletRequest request) {
        accessService.requireLocal(request);
        permissionService.require(Permission.MANAGE_SETTINGS);
    }
}
