package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.ActivateStoreRequest;
import br.com.zentrix.web.dto.ProvisionTenantRequest;
import br.com.zentrix.web.service.ProvisioningService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/pdv/activation")
public class PdvActivationController {
    private final ProvisioningService provisioningService;

    @Value("${zentrix.sync.api-key:}")
    private String syncKey;

    public PdvActivationController(ProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping("/create-account")
    public Map<String, Object> createAccount(@RequestBody CreateAccountRequest request) {
        AdminRequest admin = request == null ? null : request.admin();
        if (request == null || admin == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe os dados da conta administradora");
        }

        Map<String, Object> response = provisioningService.bootstrap(new ProvisionTenantRequest(
                request.tenantName(),
                null,
                request.storeName(),
                request.sourceId(),
                request.deviceId(),
                request.sourceId(),
                admin.username(),
                admin.displayName(),
                null,
                admin.passwordHash()
        ));
        return withSyncKey(response);
    }

    @PostMapping("/activate-device")
    public Map<String, Object> activateDevice(@RequestBody ActivateDeviceRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe os dados de ativação");
        }

        Map<String, Object> response = provisioningService.activateCode(new ActivateStoreRequest(
                request.activationCode(),
                request.deviceId(),
                request.sourceId(),
                request.sourceId()
        ));
        return withSyncKey(response);
    }

    private Map<String, Object> withSyncKey(Map<String, Object> original) {
        Map<String, Object> response = new LinkedHashMap<>(original);
        response.put("syncKey", syncKey == null ? "" : syncKey.trim());
        response.put("webSyncKey", syncKey == null ? "" : syncKey.trim());
        return response;
    }

    public record CreateAccountRequest(
            String tenantName,
            String storeName,
            String deviceId,
            String sourceId,
            AdminRequest admin
    ) {
    }

    public record AdminRequest(
            String username,
            String displayName,
            String passwordHash,
            String role
    ) {
    }

    public record ActivateDeviceRequest(
            String activationCode,
            String deviceId,
            String sourceId
    ) {
    }
}
