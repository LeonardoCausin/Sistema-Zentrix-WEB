package br.com.zentrix.web.dto;

import java.util.List;

public record LoginResponse(
        String token,
        String userName,
        String role,
        String companyId,
        String companyName,
        String storeId,
        String sourceId,
        List<String> permissions
) {
}
