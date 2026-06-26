package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ProvisionTenantRequest(
        @NotBlank String companyName,
        String document,
        String storeName,
        String sourceId,
        String deviceId,
        String deviceName,
        @NotBlank String adminUsername,
        String adminDisplayName,
        String adminPassword,
        String adminPasswordHash
) {
}
