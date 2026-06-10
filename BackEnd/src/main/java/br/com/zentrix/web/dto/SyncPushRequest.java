package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record SyncPushRequest(
        @NotBlank String sourceId,
        String mode,
        OffsetDateTime generatedAt,
        @NotNull Map<String, List<Map<String, Object>>> tables
) {
    public String normalizedMode() {
        return mode == null || mode.isBlank() ? "PARTIAL" : mode.trim().toUpperCase();
    }
}
