package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record CashMovementRequest(
        @NotNull @Positive BigDecimal value,
        @NotBlank String reason,
        String observation
) {
}
