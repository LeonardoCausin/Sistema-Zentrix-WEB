package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

public record StockAdjustmentRequest(
        @NotBlank String productCode,
        @NotNull BigDecimal quantity,
        @NotBlank String reason,
        String referenceType,
        String referenceId
) {
    public record Entry(
            @NotBlank String productCode,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank String reason,
            String referenceType,
            String referenceId
    ) {
    }
}
