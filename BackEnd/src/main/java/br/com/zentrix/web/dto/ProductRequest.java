package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record ProductRequest(
        @NotBlank String code,
        @NotBlank String description,
        String unit,
        @NotNull BigDecimal price,
        BigDecimal costPrice,
        BigDecimal stock,
        BigDecimal minStock,
        BigDecimal idealStock,
        String category,
        String barcode,
        Boolean active
) {
}
