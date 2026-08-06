package br.com.zentrix.web.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TotpService {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final SecureRandom random = new SecureRandom();
    private final boolean featureEnabled;
    private final String issuer;
    private final String encryptionKey;

    public TotpService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            @Value("${zentrix.auth.mfa.enabled:false}") boolean featureEnabled,
            @Value("${zentrix.auth.mfa.issuer:Zentrix}") String issuer,
            @Value("${zentrix.auth.mfa.secret-key:}") String encryptionKey
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.featureEnabled = featureEnabled;
        this.issuer = issuer == null || issuer.isBlank() ? "Zentrix" : issuer.trim();
        this.encryptionKey = encryptionKey == null ? "" : encryptionKey.trim();
    }

    public void requireValid(String tenantId, String username, String code) {
        if (!available()) return;
        initializer.ensureReady();
        Map<String, Object> row = jdbcTemplate.queryForList("""
                SELECT secret_base32 AS secret, enabled FROM user_mfa
                WHERE tenant_id = ? AND username = ? LIMIT 1
                """, tenantId, username).stream().findFirst().orElse(Map.of());
        if (!Boolean.TRUE.equals(row.get("enabled")) && !"1".equals(String.valueOf(row.get("enabled")))) return;
        if (!valid(unprotect(String.valueOf(row.get("secret"))), code)) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED, "Informe o codigo de 6 digitos do aplicativo autenticador.");
        }
    }

    public Map<String, Object> status(String tenantId, String username) {
        initializer.ensureReady();
        boolean enabled = jdbcTemplate.queryForList("SELECT enabled FROM user_mfa WHERE tenant_id = ? AND username = ?", tenantId, username)
                .stream().findFirst().map(row -> Boolean.TRUE.equals(row.get("enabled")) || "1".equals(String.valueOf(row.get("enabled")))).orElse(false);
        return Map.of("available", available(), "enabled", enabled);
    }

    public Map<String, Object> setup(String tenantId, String username) {
        requireFeature();
        initializer.ensureReady();
        byte[] bytes = new byte[20];
        random.nextBytes(bytes);
        String secret = encodeBase32(bytes);
        jdbcTemplate.update("""
                INSERT INTO user_mfa (tenant_id, username, secret_base32, enabled)
                VALUES (?, ?, ?, FALSE)
                ON DUPLICATE KEY UPDATE secret_base32 = VALUES(secret_base32), enabled = FALSE, verified_at = NULL
                """, tenantId, username, protect(secret));
        String label = url(issuer + ":" + username);
        String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + url(issuer) + "&digits=6&period=30";
        return Map.of("secret", secret, "otpauthUri", uri);
    }

    public Map<String, Object> enable(String tenantId, String username, String code) {
        requireFeature();
        String secret = secret(tenantId, username);
        if (!valid(secret, code)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Codigo autenticador invalido.");
        jdbcTemplate.update("UPDATE user_mfa SET enabled = TRUE, verified_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND username = ?", tenantId, username);
        return Map.of("enabled", true);
    }

    public Map<String, Object> disable(String tenantId, String username, String code) {
        requireFeature();
        String secret = secret(tenantId, username);
        if (!valid(secret, code)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Codigo autenticador invalido.");
        jdbcTemplate.update("UPDATE user_mfa SET enabled = FALSE WHERE tenant_id = ? AND username = ?", tenantId, username);
        return Map.of("enabled", false);
    }

    boolean valid(String secret, String code) {
        String normalized = code == null ? "" : code.replaceAll("\\D", "");
        if (secret == null || secret.isBlank() || normalized.length() != 6) return false;
        long step = Instant.now().getEpochSecond() / 30;
        for (long offset = -1; offset <= 1; offset++) {
            if (normalized.equals(generate(secret, step + offset))) return true;
        }
        return false;
    }

    private String generate(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(decodeBase32(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format("%06d", binary % 1_000_000);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Falha ao validar o autenticador.", error);
        }
    }

    private String secret(String tenantId, String username) {
        initializer.ensureReady();
        return jdbcTemplate.queryForList("SELECT secret_base32 AS secret FROM user_mfa WHERE tenant_id = ? AND username = ?", tenantId, username)
                .stream().findFirst().map(row -> unprotect(String.valueOf(row.get("secret")))).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Configure o autenticador antes de continuar."));
    }

    private void requireFeature() {
        if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure o segundo fator e sua chave secreta no servidor antes de continuar.");
    }

    private boolean available() {
        return featureEnabled && encryptionKey.length() >= 32;
    }

    private String protect(String secret) {
        try {
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey(), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            byte[] payload = ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array();
            return "v1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(payload);
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Falha ao proteger o autenticador.", error);
        }
    }

    private String unprotect(String value) {
        if (value == null || !value.startsWith("v1:")) return value;
        try {
            byte[] payload = Base64.getUrlDecoder().decode(value.substring(3));
            byte[] nonce = Arrays.copyOfRange(payload, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(payload, 12, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey(), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "A configuracao do autenticador nao pode ser lida.");
        }
    }

    private SecretKeySpec aesKey() throws GeneralSecurityException {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptionKey.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    private String encodeBase32(byte[] data) {
        StringBuilder result = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) {
                result.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) result.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return result.toString();
    }

    private byte[] decodeBase32(String value) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char character : value.toUpperCase().toCharArray()) {
            int index = ALPHABET.indexOf(character);
            if (index < 0) continue;
            buffer = (buffer << 5) | index;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }

    private String url(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
