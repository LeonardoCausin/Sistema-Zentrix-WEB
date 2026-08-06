package br.com.zentrix.web.controller;

import br.com.zentrix.web.service.LocalAdminAccessService;
import br.com.zentrix.web.service.PermissionService;
import br.com.zentrix.web.service.PermissionService.Permission;
import br.com.zentrix.web.service.ZentrixAdminService;
import br.com.zentrix.web.service.BillingService;
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
    private final BillingService billingService;

    public ZentrixAdminController(
            LocalAdminAccessService accessService,
            PermissionService permissionService,
            ZentrixAdminService zentrixAdminService,
            BillingService billingService
    ) {
        this.accessService = accessService;
        this.permissionService = permissionService;
        this.zentrixAdminService = zentrixAdminService;
        this.billingService = billingService;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview(HttpServletRequest request) {
        requireAnyAdminAccess(request);
        return zentrixAdminService.overview();
    }

    @GetMapping("/plans")
    public List<Map<String, Object>> plans(HttpServletRequest request) {
        requireAnyAdminAccess(request);
        return zentrixAdminService.plans();
    }

    @GetMapping("/finance")
    public Map<String, Object> finance(
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "150") int limit,
            HttpServletRequest request
    ) {
        requireFinanceAccess(request);
        return zentrixAdminService.financeOverview(status, limit);
    }

    @GetMapping("/expiration-alerts")
    public List<Map<String, Object>> expirationAlerts(HttpServletRequest request) {
        requireAnyAdminAccess(request);
        return zentrixAdminService.expirationAlerts();
    }

    @GetMapping("/clients")
    public List<Map<String, Object>> clients(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "all") String status,
            @RequestParam(defaultValue = "100") int limit,
            HttpServletRequest request
    ) {
        requireAnyAdminAccess(request);
        return zentrixAdminService.clients(search, status, limit);
    }

    @PostMapping("/clients")
    public Map<String, Object> createClient(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        requireFinanceAccess(request);
        return zentrixAdminService.createClient(body);
    }

    @GetMapping("/clients/{tenantId}")
    public Map<String, Object> client(@PathVariable String tenantId, HttpServletRequest request) {
        requireAnyAdminAccess(request);
        return zentrixAdminService.client(tenantId);
    }

    @GetMapping("/clients/{tenantId}/history")
    public List<Map<String, Object>> clientHistory(@PathVariable String tenantId, HttpServletRequest request) {
        requireAnyAdminAccess(request);
        return zentrixAdminService.clientHistory(tenantId);
    }

    @GetMapping("/clients/{tenantId}/health")
    public Map<String, Object> clientHealth(@PathVariable String tenantId, HttpServletRequest request) {
        requireAnyAdminAccess(request);
        return zentrixAdminService.clientHealth(tenantId);
    }

    @GetMapping("/clients/{tenantId}/plan-preview")
    public Map<String, Object> planPreview(
            @PathVariable String tenantId,
            @RequestParam String plan,
            HttpServletRequest request
    ) {
        requireFinanceAccess(request);
        return billingService.previewPlanChange(tenantId, plan);
    }

    @PostMapping("/clients/{tenantId}/access-test")
    public Map<String, Object> testClientAccess(@PathVariable String tenantId, HttpServletRequest request) {
        requireAnyAdminAccess(request);
        return zentrixAdminService.testClientAccess(tenantId);
    }

    @PutMapping("/clients/{tenantId}/status")
    public Map<String, Object> updateClientStatus(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireFinanceAccess(request);
        return zentrixAdminService.updateClientStatus(tenantId, body);
    }

    @PutMapping("/clients/{tenantId}/billing-profile")
    public Map<String, Object> updateBillingProfile(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireFinanceAccess(request);
        return zentrixAdminService.updateBillingProfile(tenantId, body);
    }

    @PutMapping("/clients/{tenantId}/stores/{storeId}/status")
    public Map<String, Object> updateStoreStatus(
            @PathVariable String tenantId,
            @PathVariable String storeId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireFinanceAccess(request);
        return zentrixAdminService.updateStoreStatus(tenantId, storeId, body);
    }

    @PutMapping("/clients/{tenantId}/devices/{deviceId}/billing")
    public Map<String, Object> updateDeviceBilling(
            @PathVariable String tenantId,
            @PathVariable String deviceId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireFinanceAccess(request);
        return zentrixAdminService.updateDeviceBilling(tenantId, deviceId, body);
    }

    @GetMapping("/clients/{tenantId}/support-notes")
    public List<Map<String, Object>> supportNotes(@PathVariable String tenantId, HttpServletRequest request) {
        requireAnyAdminAccess(request);
        return zentrixAdminService.supportNotes(tenantId);
    }

    @PostMapping("/clients/{tenantId}/support-notes")
    public Map<String, Object> addSupportNote(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireSupportAccess(request);
        return zentrixAdminService.addSupportNote(tenantId, body);
    }

    @PutMapping("/clients/{tenantId}/support-notes/{noteId}/resolve")
    public Map<String, Object> resolveSupportNote(
            @PathVariable String tenantId,
            @PathVariable long noteId,
            HttpServletRequest request
    ) {
        requireSupportAccess(request);
        return zentrixAdminService.resolveSupportNote(tenantId, noteId);
    }

    @PostMapping("/clients/{tenantId}/licenses")
    public Map<String, Object> createLicense(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireFinanceAccess(request);
        return zentrixAdminService.createLicense(tenantId, body);
    }

    @PostMapping("/clients/{tenantId}/activation-codes")
    public Map<String, Object> createActivationCode(
            @PathVariable String tenantId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest request
    ) {
        requireSupportAccess(request);
        return zentrixAdminService.createActivationCode(tenantId, body);
    }

    private void requireAnyAdminAccess(HttpServletRequest request) {
        accessService.requireLocal(request);
        permissionService.requireAny(
                Permission.ZENTRIX_ADMIN_OWNER,
                Permission.ZENTRIX_ADMIN_FINANCE,
                Permission.ZENTRIX_ADMIN_SUPPORT,
                Permission.MANAGE_LICENSE
        );
    }

    private void requireFinanceAccess(HttpServletRequest request) {
        accessService.requireLocal(request);
        permissionService.requireAny(
                Permission.ZENTRIX_ADMIN_OWNER,
                Permission.ZENTRIX_ADMIN_FINANCE,
                Permission.MANAGE_LICENSE
        );
    }

    private void requireSupportAccess(HttpServletRequest request) {
        accessService.requireLocal(request);
        permissionService.requireAny(
                Permission.ZENTRIX_ADMIN_OWNER,
                Permission.ZENTRIX_ADMIN_SUPPORT,
                Permission.MANAGE_LICENSE
        );
    }
}
