package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;

public record CloseCashSessionRequest(
        @NotNull @PositiveOrZero BigDecimal closingBalance,
        String observation,
        @NotBlank String reason
) {
}
