package br.com.zentrix.web.dto;

public record LoginResponse(
        String token,
        String userName,
        String role,
        String companyId,
        String companyName
) {
}
