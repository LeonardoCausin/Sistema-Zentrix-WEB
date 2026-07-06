package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;

public record FinancialEntryStatusRequest(
        @NotBlank String status,
        String reason
) {
}
