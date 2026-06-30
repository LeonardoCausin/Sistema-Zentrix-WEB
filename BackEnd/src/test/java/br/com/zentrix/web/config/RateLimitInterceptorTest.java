package br.com.zentrix.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class RateLimitInterceptorTest {

    @Test
    void blocksRequestsAfterLimitIsExceeded() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(Clock.fixed(Instant.parse("2026-06-30T12:00:00Z"), ZoneOffset.UTC));
        interceptor.setAuthLimit(2);
        interceptor.setAuthWindowSeconds(60);

        assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(request(), new MockHttpServletResponse(), new Object()));

        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request(), blocked, new Object()));
        assertEquals(429, blocked.getStatus());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
