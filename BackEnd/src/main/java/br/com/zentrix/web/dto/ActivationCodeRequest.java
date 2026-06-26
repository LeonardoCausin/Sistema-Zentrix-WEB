package br.com.zentrix.web.dto;

public record ActivationCodeRequest(
        String tenantId,
        String companyName,
        String document,
        String storeName,
        String sourceId,
        Integer expiresMinutes
) {
}
