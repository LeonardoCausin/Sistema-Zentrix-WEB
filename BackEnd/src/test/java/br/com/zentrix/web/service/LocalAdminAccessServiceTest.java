package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

class LocalAdminAccessServiceTest {

    @Test
    void acceptsPrivateClientForwardedByLocalProxy() {
        LocalAdminAccessService service = enabledService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "192.168.1.25");

        assertDoesNotThrow(() -> service.requireLocal(request));
    }

    @Test
    void rejectsSpoofedPrivatePrefixFromPublicClient() {
        LocalAdminAccessService service = enabledService();
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "192.168.1.25, 203.0.113.40");

        ResponseStatusException exception = assertThrows(ResponseStatusException.class,
                () -> service.requireLocal(request));

        assertEquals(HttpStatus.FORBIDDEN, exception.getStatusCode());
    }

    private LocalAdminAccessService enabledService() {
        LocalAdminAccessService service = new LocalAdminAccessService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "allowPrivateNetworks", true);
        return service;
    }
}
