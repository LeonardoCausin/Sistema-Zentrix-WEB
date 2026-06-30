package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import br.com.zentrix.web.dto.LoginRequest;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class AuthServiceTest {
    private FakeJdbcTemplate jdbcTemplate;
    private RecordingAuthTokenService authTokenService;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        jdbcTemplate = new FakeJdbcTemplate();
        authTokenService = new RecordingAuthTokenService();
        authService = new AuthService(jdbcTemplate, new NoopInitializer(), authTokenService, new NoopAuditService());
    }

    @Test
    void rejectsNonAdminUserEvenWhenPasswordMatches() {
        jdbcTemplate.addQueryResult(List.of(user("operador", "OPERATOR", BCrypt.hashpw("senha", BCrypt.gensalt(4)))));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> authService.login(new LoginRequest("operador", "senha")));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
        assertEquals(0, authTokenService.issuedCount);
    }

    @Test
    void issuesTokenForAdminUser() {
        jdbcTemplate.addQueryResult(List.of(user("admin", "ADMIN", BCrypt.hashpw("senha", BCrypt.gensalt(4)))));
        jdbcTemplate.addQueryResult(List.of());

        var response = authService.login(new LoginRequest("admin", "senha"));

        assertEquals("token-123", response.token());
        assertEquals("tenant-1", response.companyId());
        assertEquals(1, authTokenService.issuedCount);
    }

    private Map<String, Object> user(String username, String role, String password) {
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("tenant_id", "tenant-1");
        user.put("store_id", "store-1");
        user.put("username", username);
        user.put("password", password);
        user.put("display_name", "Administrador");
        user.put("role", role);
        user.put("source_id", "WEB");
        user.put("tenant_name", "Empresa");
        return user;
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        private final ArrayDeque<List<Map<String, Object>>> queryResults = new ArrayDeque<>();

        void addQueryResult(List<Map<String, Object>> rows) {
            queryResults.add(rows);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (queryResults.isEmpty()) {
                throw new AssertionError("Unexpected queryForList call: " + sql);
            }
            return queryResults.removeFirst();
        }

        @Override
        public int update(String sql, Object... args) {
            return 1;
        }
    }

    private static class NoopInitializer extends WebDatabaseInitializer {
        NoopInitializer() {
            super(null, null);
        }

        @Override
        public void ensureReady() {
        }
    }

    private static class RecordingAuthTokenService extends AuthTokenService {
        int issuedCount;

        @Override
        public String issue(String username, String displayName, String role, String tenantId) {
            issuedCount++;
            return "token-123";
        }
    }

    private static class NoopAuditService extends AuditService {
        NoopAuditService() {
            super(null, null);
        }

        @Override
        public void info(String action, String entityType, String entityId, String details) {
        }

        @Override
        public void record(
                String tenantId,
                String storeId,
                String deviceId,
                String sourceId,
                String user,
                String action,
                String entityType,
                String entityId,
                String details,
                String riskLevel,
                String previousValue,
                String newValue,
                String reason,
                String origin,
                String ipAddress,
                String userRole
        ) {
        }
    }
}
