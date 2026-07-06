package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotNull;

public record ProductStatusRequest(
        @NotNull Boolean active,
        String reason
) {
}
