package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.LoginRequest;
import br.com.zentrix.web.dto.LoginResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(5);

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final AuthTokenService authTokenService;
    private final AuditService auditService;
    private final Map<String, LoginAttempt> attempts = new ConcurrentHashMap<>();

    public AuthService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer, AuthTokenService authTokenService, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.authTokenService = authTokenService;
        this.auditService = auditService;
    }

    public LoginResponse login(LoginRequest request) {
        initializer.ensureReady();
        String loginName = request.email().trim();
        ensureNotLocked(loginName);
        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                SELECT u.tenant_id, u.store_id, u.username, u.password, u.display_name, u.role, u.source_id,
                       COALESCE(t.name, u.tenant_id) AS tenant_name
                FROM users u
                LEFT JOIN tenants t ON t.id = u.tenant_id
                WHERE u.username = ? AND u.active = TRUE
                ORDER BY u.tenant_id = 'legacy' DESC, u.tenant_id, u.store_id
                """, loginName);
        if (users.isEmpty()) {
            recordFailure(loginName);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais inválidas");
        }

        boolean passwordMatchedNonAdmin = false;
        for (Map<String, Object> user : users) {
            String hash = String.valueOf(user.get("password"));
            if (!passwordMatches(request.password(), hash)) {
                continue;
            }

            String username = String.valueOf(user.get("username"));
            String displayName = String.valueOf(user.get("display_name"));
            String role = String.valueOf(user.get("role"));
            Map<String, Object> sessionScope = resolveSessionScope(user);
            String tenantId = String.valueOf(sessionScope.get("tenant_id"));
            String tenantName = String.valueOf(sessionScope.get("tenant_name"));
            String storeId = String.valueOf(sessionScope.get("store_id"));
            String sourceId = String.valueOf(sessionScope.get("source_id"));

            attempts.remove(loginName.toLowerCase());
            jdbcTemplate.update("""
                    UPDATE users
                    SET last_login_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND store_id = ? AND username = ?
                    """, String.valueOf(user.get("tenant_id")), String.valueOf(user.get("store_id")), username);
            auditService.record(tenantId, storeId, null, sourceId, username, "LOGIN_SUCCESS", "users", username, "Login realizado com sucesso.", "INFO", null, null, null, "APPGESTAO", null, role);
            return new LoginResponse(
                    authTokenService.issue(username, displayName, role, tenantId),
                    displayName,
                    role,
                    tenantId,
                    tenantName
            );
        }

        recordFailure(loginName);
        if (passwordMatchedNonAdmin) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuário sem permissão de acesso ao painel");
        }
        auditService.info("LOGIN_FAILED", "users", loginName, "Falha de login para usuário informado.");
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais inválidas");
    }

    private Map<String, Object> resolveSessionScope(Map<String, Object> user) {
        String userTenantId = String.valueOf(user.get("tenant_id"));
        String userStoreId = String.valueOf(user.get("store_id"));
        String userSourceId = String.valueOf(user.get("source_id"));
        String userTenantName = String.valueOf(user.get("tenant_name"));

        List<Map<String, Object>> officialScopes = jdbcTemplate.queryForList("""
                SELECT sr.tenant_id, sr.store_id, sr.source_id, COALESCE(t.name, sr.tenant_id) AS tenant_name
                FROM sync_runs sr
                LEFT JOIN tenants t ON t.id = sr.tenant_id
                WHERE sr.status = 'SUCCESS'
                  AND sr.tenant_id <> 'legacy'
                  AND (? IS NULL OR ? = '' OR sr.source_id = ?)
                ORDER BY sr.received_at DESC, sr.id DESC
                LIMIT 1
                """, userSourceId, userSourceId, userSourceId);

        if (!officialScopes.isEmpty()) {
            return officialScopes.get(0);
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("tenant_id", userTenantId);
        fallback.put("store_id", userStoreId);
        fallback.put("source_id", userSourceId);
        fallback.put("tenant_name", userTenantName);
        return fallback;
    }

    private void ensureNotLocked(String loginName) {
        LoginAttempt attempt = attempts.get(loginName.toLowerCase());
        if (attempt == null || attempt.count() < MAX_LOGIN_ATTEMPTS) {
            return;
        }
        if (attempt.lastFailure().plus(LOCK_DURATION).isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas de acesso");
        }
        attempts.remove(loginName.toLowerCase());
    }

    private void recordFailure(String loginName) {
        attempts.merge(
                loginName.toLowerCase(),
                new LoginAttempt(1, Instant.now()),
                (current, ignored) -> new LoginAttempt(current.count() + 1, Instant.now())
        );
    }

    private boolean passwordMatches(String plainPassword, String storedPassword) {
        if (storedPassword == null || storedPassword.isBlank()) {
            return false;
        }
        if (storedPassword.startsWith("$2a$") || storedPassword.startsWith("$2b$") || storedPassword.startsWith("$2y$")) {
            return BCrypt.checkpw(plainPassword, storedPassword);
        }
        return false;
    }

    private String normalizeRole(String role) {
        String text = role == null ? "" : role.trim().toUpperCase();
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private record LoginAttempt(int count, Instant lastFailure) {
    }
}
