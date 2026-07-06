package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

public record FinancialEntryRequest(
        @NotBlank String type,
        @NotBlank String category,
        @NotBlank String description,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate entryDate,
        String status,
        String notes
) {
}
