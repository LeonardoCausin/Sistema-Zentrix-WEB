package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record PermissionUpdateRequest(
        @NotNull List<String> permissions
) {
}
