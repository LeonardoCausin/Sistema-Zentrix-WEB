package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.LoginRequest;
import br.com.zentrix.web.dto.LoginResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {
    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final Duration LOCK_DURATION = Duration.ofMinutes(5);
    private static final ObjectMapper JSON = new ObjectMapper();

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
                       u.permissions_json, COALESCE(t.name, u.tenant_id) AS tenant_name
                FROM users u
                LEFT JOIN tenants t ON t.id = u.tenant_id
                WHERE u.username = ? AND u.active = TRUE
                ORDER BY u.tenant_id = 'legacy' DESC, u.tenant_id, u.store_id
                """, loginName);
        if (users.isEmpty()) {
            recordFailure(loginName);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário ou senha inválidos.");
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
            if (!isWebAdminRole(role)) {
                passwordMatchedNonAdmin = true;
                continue;
            }
            Map<String, Object> sessionScope = resolveSessionScope(user);
            String tenantId = String.valueOf(sessionScope.get("tenant_id"));
            String tenantName = String.valueOf(sessionScope.get("tenant_name"));
            String storeId = String.valueOf(sessionScope.get("store_id"));
            String sourceId = String.valueOf(sessionScope.get("source_id"));
            List<String> permissions = permissionsFrom(user.get("permissions_json"));

            attempts.remove(loginName.toLowerCase());
            jdbcTemplate.update("""
                    UPDATE users
                    SET last_login_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND store_id = ? AND username = ?
                    """, String.valueOf(user.get("tenant_id")), String.valueOf(user.get("store_id")), username);
            auditService.record(tenantId, storeId, null, sourceId, username, "LOGIN_SUCCESS", "users", username, "Login realizado com sucesso.", "INFO", null, null, null, "APPGESTAO", null, role);
            return new LoginResponse(
                    authTokenService.issue(username, displayName, role, tenantId, storeId, sourceId, permissions),
                    displayName,
                    role,
                    tenantId,
                    tenantName,
                    storeId,
                    sourceId,
                    permissions
            );
        }

        recordFailure(loginName);
        if (passwordMatchedNonAdmin) {
            log.warn("Login bloqueado: usuario={} senha confere, mas papeis ativos nao autorizam painel. roles={}", loginName, loginRoles(users));
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este usuário não tem permissão para acessar o painel. Peça liberação ao responsável.");
        }
        auditService.info("LOGIN_FAILED", "users", loginName, "Falha de login para usuário informado.");
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuário ou senha inválidos.");
    }

    private Map<String, Object> resolveSessionScope(Map<String, Object> user) {
        String userTenantId = stringValue(user.get("tenant_id"));
        String userStoreId = stringValue(user.get("store_id"));
        String userSourceId = stringValue(user.get("source_id"));
        String userTenantName = stringValue(user.get("tenant_name"));

        List<Map<String, Object>> officialScopes = jdbcTemplate.queryForList("""
                SELECT sr.tenant_id, sr.store_id, sr.source_id, COALESCE(t.name, sr.tenant_id) AS tenant_name
                FROM sync_runs sr
                LEFT JOIN tenants t ON t.id = sr.tenant_id
                WHERE sr.status = 'SUCCESS'
                  AND sr.tenant_id <> 'legacy'
                  AND sr.tenant_id = ?
                  AND sr.store_id = ?
                  AND (? IS NULL OR ? = '' OR sr.source_id = ?)
                ORDER BY sr.received_at DESC, sr.id DESC
                LIMIT 1
                """, userTenantId, userStoreId, userSourceId, userSourceId, userSourceId);

        if (!officialScopes.isEmpty()) {
            return officialScopes.get(0);
        }

        if ("legacy".equalsIgnoreCase(userTenantId) && hasText(userSourceId)) {
            List<Map<String, Object>> sourceScopes = jdbcTemplate.queryForList("""
                    SELECT sr.tenant_id, sr.store_id, sr.source_id, COALESCE(t.name, sr.tenant_id) AS tenant_name
                    FROM sync_runs sr
                    LEFT JOIN tenants t ON t.id = sr.tenant_id
                    WHERE sr.status = 'SUCCESS'
                      AND sr.tenant_id <> 'legacy'
                      AND sr.source_id = ?
                    ORDER BY sr.received_at DESC, sr.id DESC
                    LIMIT 1
                    """, userSourceId);
            if (!sourceScopes.isEmpty()) {
                return sourceScopes.get(0);
            }
        }

        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("tenant_id", userTenantId);
        fallback.put("store_id", userStoreId);
        fallback.put("source_id", userSourceId);
        fallback.put("tenant_name", userTenantName);
        return fallback;
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void ensureNotLocked(String loginName) {
        LoginAttempt attempt = attempts.get(loginName.toLowerCase());
        if (attempt == null || attempt.count() < MAX_LOGIN_ATTEMPTS) {
            return;
        }
        if (attempt.lastFailure().plus(LOCK_DURATION).isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Muitas tentativas seguidas. Aguarde alguns minutos e tente novamente.");
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

    private List<String> loginRoles(List<Map<String, Object>> users) {
        return users.stream()
                .map(user -> "tenant=" + stringValue(user.get("tenant_id"))
                        + ", store=" + stringValue(user.get("store_id"))
                        + ", role=" + stringValue(user.get("role")))
                .toList();
    }

    private boolean isWebAdminRole(String role) {
        return switch (normalizeRole(role)) {
            case "ADMIN", "ADMINISTRADOR", "ADMINISTRATOR", "DONO", "OWNER" -> true;
            default -> false;
        };
    }

    private String normalizeRole(String role) {
        String text = role == null ? "" : role.trim().toUpperCase();
        return Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }

    private List<String> permissionsFrom(Object value) {
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return List.of();
        }
        try {
            return JSON.readValue(text, new TypeReference<List<String>>() {
            });
        } catch (Exception ignored) {
            String cleaned = text.replace("[", "").replace("]", "");
            List<String> permissions = new ArrayList<>();
            for (String item : cleaned.split(",")) {
                String permission = item.trim().replace("\"", "");
                if (!permission.isBlank()) {
                    permissions.add(permission);
                }
            }
            return permissions;
        }
    }

    private record LoginAttempt(int count, Instant lastFailure) {
    }
}
