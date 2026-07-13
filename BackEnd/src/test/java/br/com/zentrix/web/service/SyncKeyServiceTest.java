package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
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

    private SyncKeyService serviceWithKey(String key) {
        SyncKeyService service = new SyncKeyService();
        ReflectionTestUtils.setField(service, "syncApiKey", key);
        return service;
    }
}
