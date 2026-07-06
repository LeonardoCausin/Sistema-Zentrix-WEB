package br.com.zentrix.web.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, SessionToken> tokens = new ConcurrentHashMap<>();
    private Duration tokenTtl = Duration.ofHours(8);

    @org.springframework.beans.factory.annotation.Value("${zentrix.auth.token-ttl-minutes:480}")
    public void setTokenTtlMinutes(long tokenTtlMinutes) {
        this.tokenTtl = Duration.ofMinutes(Math.max(5, tokenTtlMinutes));
    }

    public String issue(String username, String displayName, String role, String tenantId) {
        return issue(username, displayName, role, tenantId, List.of());
    }

    public String issue(String username, String displayName, String role, String tenantId, List<String> permissions) {
        return issue(username, displayName, role, tenantId, null, null, permissions);
    }

    public String issue(String username, String displayName, String role, String tenantId, String storeId, String sourceId, List<String> permissions) {
        purgeExpired();
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant now = Instant.now();
        tokens.put(token, new SessionToken(username, displayName, role, tenantId, normalizeStore(storeId), normalizeSource(sourceId), normalizePermissions(permissions), now, now.plus(tokenTtl)));
        return token;
    }

    public Optional<SessionToken> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        SessionToken session = tokens.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            tokens.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            tokens.remove(token);
        }
    }

    public int revokeUser(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        int before = tokens.size();
        tokens.entrySet().removeIf(entry -> username.equalsIgnoreCase(entry.getValue().username()));
        return before - tokens.size();
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    private Set<String> normalizePermissions(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank()) {
                normalized.add(permission.trim().toLowerCase());
            }
        }
        return Set.copyOf(normalized);
    }

    private String normalizeStore(String storeId) {
        return storeId == null || storeId.isBlank() ? "WEB" : storeId.trim();
    }

    private String normalizeSource(String sourceId) {
        return sourceId == null || sourceId.isBlank() ? "WEB" : sourceId.trim();
    }

    public record SessionToken(
            String username,
            String displayName,
            String role,
            String tenantId,
            String storeId,
            String sourceId,
            Set<String> permissions,
            Instant issuedAt,
            Instant expiresAt
    ) {
        public SessionToken(String username, String displayName, String role, String tenantId, Set<String> permissions, Instant issuedAt, Instant expiresAt) {
            this(username, displayName, role, tenantId, "WEB", "WEB", permissions, issuedAt, expiresAt);
        }
    }
}
