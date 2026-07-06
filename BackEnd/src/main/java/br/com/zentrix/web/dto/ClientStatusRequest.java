package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotNull;

public record ClientStatusRequest(
        @NotNull Boolean active,
        String reason
) {
}
