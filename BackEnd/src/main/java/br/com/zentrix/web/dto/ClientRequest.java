package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

public record ClientRequest(
        Integer id,
        @NotBlank String name,
        String cpfCnpj,
        String phone,
        String email,
        String address,
        LocalDate birthDate,
        Boolean active,
        String notes,
        Integer loyaltyPoints
) {
}
