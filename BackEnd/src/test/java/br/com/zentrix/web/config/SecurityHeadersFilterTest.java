package br.com.zentrix.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
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
    }
}
