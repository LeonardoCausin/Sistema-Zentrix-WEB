package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class SyncKeyServiceTest {

    @Test
    void acceptsConfiguredSyncKey() {
        SyncKeyService service = serviceWithKey("sync-secret");

        assertDoesNotThrow(() -> service.require("sync-secret"));
    }

    @Test
    void rejectsMissingConfiguredSyncKey() {
        SyncKeyService service = serviceWithKey("");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.require("sync-secret"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }

    @Test
    void rejectsWrongSyncKey() {
        SyncKeyService service = serviceWithKey("sync-secret");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.require("wrong"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    void acceptsDeviceSpecificKeyWithoutExposingGlobalKey() throws Exception {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        SyncKeyService service = new SyncKeyService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "syncApiKey", "global-secret");
        String deviceKey = "device-specific-secret";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(deviceKey.getBytes(StandardCharsets.UTF_8)));
        jdbcTemplate.rows = List.of(Map.of("syncKeyHash", hash, "status", "ACTIVE"));

        assertDoesNotThrow(() -> service.requireForDevice(deviceKey, "tenant-1", "store-1", "device-1"));
    }

    @Test
    void rejectsInactiveDeviceEvenWithItsOwnKey() throws Exception {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        SyncKeyService service = new SyncKeyService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "syncApiKey", "global-secret");
        String deviceKey = "device-specific-secret";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(deviceKey.getBytes(StandardCharsets.UTF_8)));
        jdbcTemplate.rows = List.of(Map.of("syncKeyHash", hash, "status", "BLOCKED"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.requireForDevice(deviceKey, "tenant-1", "store-1", "device-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    void rejectsInactiveKnownDeviceEvenWithLegacyGlobalKey() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        SyncKeyService service = new SyncKeyService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "syncApiKey", "global-secret");
        jdbcTemplate.rows = List.of(Map.of("syncKeyHash", "", "status", "BLOCKED"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.requireForDevice("global-secret", "tenant-1", "store-1", "device-1"));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    @Test
    void rejectsGlobalKeyWhenDeviceAlreadyHasItsOwnCredential() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        SyncKeyService service = new SyncKeyService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "syncApiKey", "global-secret");
        jdbcTemplate.rows = List.of(Map.of("syncKeyHash", "device-hash", "status", "ACTIVE"));

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.requireForDevice("global-secret", "tenant-1", "store-1", "device-1"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    @Test
    void acceptsGlobalKeyOnlyForLegacyDeviceWithoutOwnCredential() {
        FakeJdbcTemplate jdbcTemplate = new FakeJdbcTemplate();
        SyncKeyService service = new SyncKeyService(jdbcTemplate);
        ReflectionTestUtils.setField(service, "syncApiKey", "global-secret");
        jdbcTemplate.rows = List.of(Map.of("syncKeyHash", "", "status", "ACTIVE"));

        assertDoesNotThrow(() -> service.requireForDevice(
                "global-secret", "tenant-1", "store-1", "legacy-device"));
    }

    private SyncKeyService serviceWithKey(String key) {
        SyncKeyService service = new SyncKeyService();
        ReflectionTestUtils.setField(service, "syncApiKey", key);
        return service;
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        List<Map<String, Object>> rows = List.of();

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return rows;
        }
    }
}
