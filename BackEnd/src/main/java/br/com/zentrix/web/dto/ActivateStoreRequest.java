package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;

public record ActivateStoreRequest(
        @NotBlank String code,
        String deviceId,
        String deviceName,
        String sourceId
) {
}
