package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.LoginRequest;
import br.com.zentrix.web.dto.LoginResponse;
import java.time.Duration;
import java.time.Instant;
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
    private final Map<String, LoginAttempt> attempts = new ConcurrentHashMap<>();

    public AuthService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer, AuthTokenService authTokenService) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.authTokenService = authTokenService;
    }

    public LoginResponse login(LoginRequest request) {
        initializer.ensureReady();
        String loginName = request.email().trim();
        ensureNotLocked(loginName);
        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                SELECT username, password, display_name, role
                FROM users
                WHERE username = ? AND active = TRUE
                LIMIT 1
                """, loginName);
        if (users.isEmpty()) {
            recordFailure(loginName);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais invalidas");
        }

        Map<String, Object> user = users.get(0);
        String hash = String.valueOf(user.get("password"));
        if (!passwordMatches(request.password(), hash)) {
            recordFailure(loginName);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais invalidas");
        }

        String username = String.valueOf(user.get("username"));
        String displayName = String.valueOf(user.get("display_name"));
        String role = String.valueOf(user.get("role"));

        attempts.remove(loginName.toLowerCase());
        return new LoginResponse(
                authTokenService.issue(username, displayName, role),
                displayName,
                role,
                "WEB-001",
                "Zentrix Web"
        );
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

    private record LoginAttempt(int count, Instant lastFailure) {
    }
}
