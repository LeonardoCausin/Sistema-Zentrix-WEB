package br.com.zentrix.web.service;

import java.security.SecureRandom;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, SessionToken> tokens = new ConcurrentHashMap<>();
    private Duration tokenTtl = Duration.ofHours(8);
    private JdbcTemplate jdbcTemplate;
    private WebDatabaseInitializer initializer;
    private boolean persistenceEnabled = true;

    @Autowired(required = false)
    void configurePersistence(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
    }

    @Value("${zentrix.auth.session-persistence-enabled:true}")
    void setPersistenceEnabled(boolean persistenceEnabled) {
        this.persistenceEnabled = persistenceEnabled;
    }

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
        SessionToken session = new SessionToken(username, displayName, role, tenantId, normalizeStore(storeId), normalizeSource(sourceId), normalizePermissions(permissions), now, now.plus(tokenTtl));
        tokens.put(token, session);
        persist(token, session);
        return token;
    }

    public Optional<SessionToken> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        SessionToken session = tokens.get(token);
        if (session == null) {
            session = loadPersisted(token).orElse(null);
            if (session == null) {
                return Optional.empty();
            }
            tokens.put(token, session);
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
            if (persistenceAvailable()) {
                jdbcTemplate.update("UPDATE auth_sessions SET revoked_at = CURRENT_TIMESTAMP WHERE token_hash = ?", hash(token));
            }
        }
    }

    public int revokeUser(String username) {
        if (username == null || username.isBlank()) {
            return 0;
        }
        int before = tokens.size();
        tokens.entrySet().removeIf(entry -> username.equalsIgnoreCase(entry.getValue().username()));
        int revoked = before - tokens.size();
        if (persistenceAvailable()) {
            revoked = Math.max(revoked, jdbcTemplate.update("""
                    UPDATE auth_sessions
                    SET revoked_at = CURRENT_TIMESTAMP
                    WHERE LOWER(username) = LOWER(?) AND revoked_at IS NULL
                    """, username));
        }
        return revoked;
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        if (persistenceAvailable()) {
            jdbcTemplate.update("DELETE FROM auth_sessions WHERE expires_at < DATE_SUB(NOW(), INTERVAL 1 DAY)");
        }
    }

    private void persist(String token, SessionToken session) {
        if (!persistenceAvailable()) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO auth_sessions
                    (token_hash, username, display_name, role, tenant_id, store_id, source_id,
                     permissions_json, issued_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, hash(token), session.username(), session.displayName(), session.role(), session.tenantId(),
                session.storeId(), session.sourceId(), String.join(",", session.permissions()),
                Timestamp.from(session.issuedAt()), Timestamp.from(session.expiresAt()));
    }

    private Optional<SessionToken> loadPersisted(String token) {
        if (!persistenceAvailable()) {
            return Optional.empty();
        }
        return jdbcTemplate.query("""
                SELECT username, display_name, role, tenant_id, store_id, source_id,
                       permissions_json, issued_at, expires_at
                FROM auth_sessions
                WHERE token_hash = ? AND revoked_at IS NULL AND expires_at > CURRENT_TIMESTAMP
                LIMIT 1
                """, (rs, rowNum) -> new SessionToken(
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("role"),
                rs.getString("tenant_id"),
                rs.getString("store_id"),
                rs.getString("source_id"),
                parsePermissions(rs.getString("permissions_json")),
                rs.getTimestamp("issued_at").toInstant(),
                rs.getTimestamp("expires_at").toInstant()
        ), hash(token)).stream().findFirst();
    }

    private Set<String> parsePermissions(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return normalizePermissions(List.of(value.split(",")));
    }

    private boolean persistenceAvailable() {
        if (!persistenceEnabled || jdbcTemplate == null || initializer == null) {
            return false;
        }
        initializer.ensureReady();
        return true;
    }

    private String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("Nao foi possivel proteger a sessao.", exception);
        }
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
