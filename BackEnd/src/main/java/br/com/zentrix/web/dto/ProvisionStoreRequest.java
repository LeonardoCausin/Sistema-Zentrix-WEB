package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProvisionStoreRequest(
        @NotBlank String tenantId,
        String storeName,
        String sourceId,
        String deviceId,
        String deviceName
) {
}
