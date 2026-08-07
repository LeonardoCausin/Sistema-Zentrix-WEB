package br.com.zentrix.web.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncKeyService {
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    @Value("${zentrix.sync.api-key:}")
    private String syncApiKey;
    private JdbcTemplate jdbcTemplate;

    public SyncKeyService() {
    }

    @Autowired
    public SyncKeyService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void require(String syncKey) {
        if (syncApiKey == null || syncApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure ZENTRIX_SYNC_KEY antes de sincronizar");
        }
        if (!matches(syncApiKey, syncKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chave de sincronização inválida");
        }
    }

    public void requireForDevice(String syncKey, String tenantId, String storeId, String deviceId) {
        boolean globalKeyMatches = matches(syncApiKey, syncKey);
        if (jdbcTemplate == null || blank(tenantId) || blank(storeId) || blank(deviceId)) {
            if (globalKeyMatches) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credencial do PDV inválida");
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT sync_key_hash AS syncKeyHash, status
                FROM tenant_devices
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                LIMIT 1
                """, tenantId.trim(), storeId.trim(), deviceId.trim());
        if (rows.isEmpty() && globalKeyMatches) {
            return;
        }
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Este PDV não está cadastrado para esta loja");
        }
        String status = String.valueOf(rows.get(0).getOrDefault("status", "ACTIVE"));
        if (!"ACTIVE".equalsIgnoreCase(status)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Este PDV está inativo ou bloqueado");
        }
        String expectedHash = String.valueOf(rows.get(0).getOrDefault("syncKeyHash", ""));
        if (blank(expectedHash)) {
            if (globalKeyMatches) {
                return;
            }
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credencial do PDV inválida");
        }
        if (blank(syncKey)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credencial do PDV inválida");
        }
        if (!matches(expectedHash, sha256(syncKey.trim()))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credencial do PDV inválida");
        }
    }

    public String issueDeviceKey(String tenantId, String storeId, String deviceId) {
        if (jdbcTemplate == null) {
            throw new IllegalStateException("Banco indisponível para emitir a credencial do PDV");
        }
        byte[] random = new byte[32];
        SECURE_RANDOM.nextBytes(random);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        int updated = jdbcTemplate.update("""
                UPDATE tenant_devices
                SET sync_key_hash = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, sha256(key), tenantId, storeId, deviceId);
        if (updated != 1) {
            throw new IllegalStateException("Não foi possível emitir a credencial do PDV");
        }
        return key;
    }

    private boolean matches(String expected, String received) {
        byte[] expectedBytes = expected == null ? new byte[0] : expected.trim().getBytes(StandardCharsets.UTF_8);
        byte[] receivedBytes = received == null ? new byte[0] : received.trim().getBytes(StandardCharsets.UTF_8);
        return expectedBytes.length > 0 && MessageDigest.isEqual(expectedBytes, receivedBytes);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 indisponível", exception);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
