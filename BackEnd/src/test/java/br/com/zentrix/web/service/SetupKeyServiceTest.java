package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class SetupKeyServiceTest {

    @Test
    void acceptsConfiguredSetupKey() {
        SetupKeyService service = serviceWithKey("setup-secret");

        assertDoesNotThrow(() -> service.require("setup-secret"));
    }

    @Test
    void rejectsMissingConfiguredSetupKey() {
        SetupKeyService service = serviceWithKey("");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.require("setup-secret"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, exception.getStatusCode());
    }

    @Test
    void rejectsWrongSetupKey() {
        SetupKeyService service = serviceWithKey("setup-secret");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class, () -> service.require("wrong"));

        assertEquals(HttpStatus.UNAUTHORIZED, exception.getStatusCode());
    }

    private SetupKeyService serviceWithKey(String key) {
        SetupKeyService service = new SetupKeyService();
        ReflectionTestUtils.setField(service, "setupApiKey", key);
        return service;
    }
}
