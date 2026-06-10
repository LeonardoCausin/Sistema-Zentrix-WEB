package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.LoginRequest;
import br.com.zentrix.web.dto.LoginResponse;
import java.util.List;
import java.util.Map;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final AuthTokenService authTokenService;

    public AuthService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer, AuthTokenService authTokenService) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.authTokenService = authTokenService;
    }

    public LoginResponse login(LoginRequest request) {
        initializer.ensureReady();
        String loginName = request.email().trim();
        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                SELECT username, password, display_name, role
                FROM users
                WHERE username = ? AND active = TRUE
                LIMIT 1
                """, loginName);
        if (users.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais invalidas");
        }

        Map<String, Object> user = users.get(0);
        String hash = String.valueOf(user.get("password"));
        if (!passwordMatches(request.password(), hash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciais invalidas");
        }

        String username = String.valueOf(user.get("username"));
        String displayName = String.valueOf(user.get("display_name"));
        String role = String.valueOf(user.get("role"));

        return new LoginResponse(
                authTokenService.issue(username, displayName, role),
                displayName,
                role,
                "WEB-001",
                "Zentrix Web"
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
}
