package br.com.zentrix.web.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.mindrot.jbcrypt.BCrypt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PasswordResetService {
    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final AuthTokenService tokenService;
    private final JavaMailSender mailSender;
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final String publicUrl;
    private final String mailFrom;

    public PasswordResetService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            AuthTokenService tokenService,
            JavaMailSender mailSender,
            @Value("${zentrix.auth.password-reset.enabled:false}") boolean enabled,
            @Value("${zentrix.auth.password-reset.public-url:}") String publicUrl,
            @Value("${ZENTRIX_BILLING_MAIL_FROM:}") String mailFrom
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.tokenService = tokenService;
        this.mailSender = mailSender;
        this.enabled = enabled;
        this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", "");
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
    }

    public Map<String, Object> request(String username) {
        requireEnabled();
        initializer.ensureReady();
        String login = username == null ? "" : username.trim();
        List<Map<String, Object>> users = jdbcTemplate.queryForList("""
                SELECT u.tenant_id AS tenantId, u.username,
                       COALESCE(NULLIF(t.billing_email, ''), CASE WHEN u.username LIKE '%@%' THEN u.username ELSE NULL END) AS email
                FROM users u LEFT JOIN tenants t ON t.id = u.tenant_id
                WHERE LOWER(u.username) = LOWER(?) AND u.active = TRUE
                ORDER BY u.tenant_id = 'legacy' DESC LIMIT 1
                """, login);
        if (!users.isEmpty() && users.get(0).get("email") != null) {
            send(users.get(0));
        }
        return Map.of("accepted", true, "message", "Se houver um e-mail de cobranca vinculado, enviaremos as instrucoes de recuperacao.");
    }

    @Transactional
    public Map<String, Object> confirm(String token, String newPassword) {
        requireEnabled();
        initializer.ensureReady();
        if (newPassword == null || newPassword.length() < 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A nova senha deve ter pelo menos 10 caracteres.");
        }
        Map<String, Object> reset = jdbcTemplate.queryForList("""
                SELECT tenant_id AS tenantId, username FROM password_reset_tokens
                WHERE token_hash = ? AND used_at IS NULL AND expires_at > CURRENT_TIMESTAMP LIMIT 1
                """, hash(token)).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "O link de recuperacao e invalido ou expirou."));
        jdbcTemplate.update("""
                UPDATE users SET password = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND username = ?
                """, BCrypt.hashpw(newPassword, BCrypt.gensalt(12)), reset.get("tenantId"), reset.get("username"));
        jdbcTemplate.update("UPDATE password_reset_tokens SET used_at = CURRENT_TIMESTAMP WHERE token_hash = ?", hash(token));
        tokenService.revokeUser(String.valueOf(reset.get("username")));
        return Map.of("updated", true);
    }

    private void send(Map<String, Object> user) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        jdbcTemplate.update("""
                INSERT INTO password_reset_tokens (token_hash, tenant_id, username, expires_at)
                VALUES (?, ?, ?, ?)
                """, hash(token), user.get("tenantId"), user.get("username"), Timestamp.valueOf(LocalDateTime.now().plusMinutes(30)));
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            if (!mailFrom.isBlank()) message.setFrom(mailFrom);
            message.setTo(String.valueOf(user.get("email")));
            message.setSubject("Recuperacao de senha Zentrix");
            message.setText("Use este link em ate 30 minutos para definir uma nova senha: " + publicUrl + "/?resetToken=" + token);
            mailSender.send(message);
        } catch (RuntimeException error) {
            jdbcTemplate.update("DELETE FROM password_reset_tokens WHERE token_hash = ?", hash(token));
            log.warn("Falha ao enviar recuperacao de senha: {}", error.getMessage());
        }
    }

    private void requireEnabled() {
        if (!enabled || publicUrl.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "A recuperacao por e-mail ainda nao foi configurada no servidor.");
        }
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("Falha ao proteger o link de recuperacao.", error);
        }
    }
}
