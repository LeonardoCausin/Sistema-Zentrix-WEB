package br.com.zentrix.web.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record SyncAckRequest(
        @NotEmpty List<Long> ids,
        String status,
        String error,
        Boolean retryable
) {
    public String normalizedStatus() {
        return status == null || status.isBlank() ? "ACKED" : status.trim().toUpperCase();
    }

    public boolean shouldRetry() {
        return retryable == null || retryable;
    }
}
