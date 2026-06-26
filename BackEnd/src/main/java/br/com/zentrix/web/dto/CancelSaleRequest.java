package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;

public record CancelSaleRequest(
        @NotBlank String reason
) {
}
