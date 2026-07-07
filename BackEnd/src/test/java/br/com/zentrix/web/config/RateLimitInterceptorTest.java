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

    @Test
    void ignoresForwardedForByDefault() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(Clock.fixed(Instant.parse("2026-06-30T12:00:00Z"), ZoneOffset.UTC));
        interceptor.setAuthLimit(1);
        interceptor.setAuthWindowSeconds(60);

        MockHttpServletRequest first = request();
        first.addHeader("X-Forwarded-For", "10.0.0.10");
        MockHttpServletRequest second = request();
        second.addHeader("X-Forwarded-For", "10.0.0.11");

        assertTrue(interceptor.preHandle(first, new MockHttpServletResponse(), new Object()));
        MockHttpServletResponse blocked = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(second, blocked, new Object()));
        assertEquals(429, blocked.getStatus());
    }

    @Test
    void canTrustForwardedForWhenBehindProxy() throws Exception {
        RateLimitInterceptor interceptor = new RateLimitInterceptor(Clock.fixed(Instant.parse("2026-06-30T12:00:00Z"), ZoneOffset.UTC));
        interceptor.setAuthLimit(1);
        interceptor.setAuthWindowSeconds(60);
        interceptor.setTrustProxyHeaders(true);

        MockHttpServletRequest first = request();
        first.addHeader("X-Forwarded-For", "10.0.0.10");
        MockHttpServletRequest second = request();
        second.addHeader("X-Forwarded-For", "10.0.0.11");

        assertTrue(interceptor.preHandle(first, new MockHttpServletResponse(), new Object()));
        assertTrue(interceptor.preHandle(second, new MockHttpServletResponse(), new Object()));
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setRemoteAddr("127.0.0.1");
        return request;
    }
}
