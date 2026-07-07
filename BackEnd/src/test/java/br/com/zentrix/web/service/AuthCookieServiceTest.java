package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthCookieServiceTest {

    @Test
    void writesHttpOnlyCookie() {
        AuthCookieService service = new AuthCookieService();
        MockHttpServletResponse response = new MockHttpServletResponse();

        service.writeToken(response, "token-123");

        String setCookie = response.getHeader("Set-Cookie");
        assertTrue(setCookie.contains("ZENTRIX_AUTH=token-123"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));
    }
}
