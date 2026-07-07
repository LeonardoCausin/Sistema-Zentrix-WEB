package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.ProvisionStoreRequest;
import br.com.zentrix.web.dto.ProvisionTenantRequest;
import br.com.zentrix.web.dto.ActivateStoreRequest;
import br.com.zentrix.web.dto.ActivationCodeRequest;
import br.com.zentrix.web.service.ProvisioningService;
import br.com.zentrix.web.service.SyncKeyService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/provisioning")
public class ProvisioningController {
    private final ProvisioningService provisioningService;
    private final SyncKeyService syncKeyService;

    public ProvisioningController(ProvisioningService provisioningService, SyncKeyService syncKeyService) {
        this.provisioningService = provisioningService;
        this.syncKeyService = syncKeyService;
    }

    @PostMapping("/bootstrap")
    public Map<String, Object> bootstrap(
            @RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey,
            @Valid @RequestBody ProvisionTenantRequest request
    ) {
        syncKeyService.require(syncKey);
        try {
            return provisioningService.bootstrap(request);
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Não foi possível ativar agora. Tente novamente em instantes.", e);
        }
    }

    @PostMapping("/stores")
    public Map<String, Object> store(
            @RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey,
            @Valid @RequestBody ProvisionStoreRequest request
    ) {
        syncKeyService.require(syncKey);
        try {
            return provisioningService.addStore(request);
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Não foi possível ativar agora. Tente novamente em instantes.", e);
        }
    }

    @PostMapping("/activation-codes")
    public Map<String, Object> activationCode(
            @RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey,
            @RequestBody ActivationCodeRequest request
    ) {
        syncKeyService.require(syncKey);
        try {
            return provisioningService.createActivationCode(request);
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Não foi possível ativar agora. Tente novamente em instantes.", e);
        }
    }

    @PostMapping("/activate")
    public Map<String, Object> activate(@Valid @RequestBody ActivateStoreRequest request) {
        try {
            return provisioningService.activateCode(request);
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Não foi possível ativar agora. Tente novamente em instantes.", e);
        }
    }
}
