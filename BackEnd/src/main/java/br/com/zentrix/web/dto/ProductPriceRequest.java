package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProductPriceRequest(
        @NotNull BigDecimal price,
        BigDecimal costPrice,
        String reason
) {
}
