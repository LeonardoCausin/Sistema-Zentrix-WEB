package br.com.zentrix.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class SecurityHeadersFilterTest {

    @Test
    void addsSecurityHeadersAndNoStoreForApi() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
        };

        filter.doFilter(request, response, chain);

        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"));
        assertEquals("DENY", response.getHeader("X-Frame-Options"));
        assertEquals("no-referrer", response.getHeader("Referrer-Policy"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getHeader("Content-Security-Policy").contains("script-src 'self' 'unsafe-inline'"));
    }

    @Test
    void includesConfiguredOriginsInConnectSrc() throws Exception {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("zentrix.cors.allowed-origins-csv", "http://192.168.1.240,http://painel.local:5500")
                .withProperty("zentrix.security.csp-connect-src", "https://api.zentrix.test");
        SecurityHeadersFilter filter = new SecurityHeadersFilter(environment);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/dashboard");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = (servletRequest, servletResponse) -> {
        };

        filter.doFilter(request, response, chain);

        String csp = response.getHeader("Content-Security-Policy");
        assertTrue(csp.contains("http://192.168.1.240:8080"));
        assertTrue(csp.contains("http://painel.local:8080"));
        assertTrue(csp.contains("https://api.zentrix.test"));
    }
}
