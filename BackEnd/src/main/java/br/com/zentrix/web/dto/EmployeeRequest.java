package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;

public record EmployeeRequest(
        @NotBlank String username,
        String password,
        String passwordHash,
        @NotBlank String displayName,
        @NotBlank String role,
        Boolean active
) {
}
