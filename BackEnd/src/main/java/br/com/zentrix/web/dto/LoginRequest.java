package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password,
        String otp
) {
    public LoginRequest(String email, String password) {
        this(email, password, null);
    }
}
