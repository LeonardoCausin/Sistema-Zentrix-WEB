package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.ActivateStoreRequest;
import br.com.zentrix.web.dto.ProvisionTenantRequest;
import br.com.zentrix.web.service.ProvisioningService;
import br.com.zentrix.web.service.SetupKeyService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/pdv/activation")
public class PdvActivationController {
    private final ProvisioningService provisioningService;
    private final SetupKeyService setupKeyService;

    public PdvActivationController(ProvisioningService provisioningService, SetupKeyService setupKeyService) {
        this.provisioningService = provisioningService;
        this.setupKeyService = setupKeyService;
    }

    @PostMapping("/create-account")
    public Map<String, Object> createAccount(
            @RequestHeader(value = "X-Zentrix-Setup-Key", required = false) String setupKey,
            @RequestBody CreateAccountRequest request
    ) {
        setupKeyService.require(setupKey);
        AdminRequest admin = request == null ? null : request.admin();
        if (request == null || admin == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe os dados da conta administradora");
        }

        return provisioningService.bootstrap(new ProvisionTenantRequest(
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
    }

    @PostMapping("/activate-device")
    public Map<String, Object> activateDevice(
            @RequestHeader(value = "X-Zentrix-Setup-Key", required = false) String setupKey,
            @RequestBody ActivateDeviceRequest request
    ) {
        setupKeyService.require(setupKey);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe os dados de ativacao");
        }

        return provisioningService.activateCode(new ActivateStoreRequest(
                request.activationCode(),
                request.deviceId(),
                request.sourceId(),
                request.sourceId()
        ));
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
