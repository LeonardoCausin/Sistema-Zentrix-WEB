package br.com.zentrix.web.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.AuthCookieService;
import br.com.zentrix.web.service.AuthTokenService;
import jakarta.servlet.http.Cookie;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ApiAuthInterceptorTest {

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    void acceptsHttpOnlyCookieTokenWhenBearerIsAbsent() throws Exception {
        AuthTokenService tokenService = new AuthTokenService();
        AuthCookieService cookieService = new AuthCookieService();
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(tokenService, cookieService);
        String token = tokenService.issue("admin", "Administrador", "ADMIN", "tenant-1", "store-1", "PDV-1", List.of("*"));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/auth/me");
        request.setCookies(new Cookie("ZENTRIX_AUTH", token));
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertTrue(interceptor.preHandle(request, response, new Object()));
        assertEquals("admin", AuthContext.current().orElseThrow().username());
    }

    @Test
    void rejectsRequestWithoutBearerOrCookie() throws Exception {
        ApiAuthInterceptor interceptor = new ApiAuthInterceptor(new AuthTokenService(), new AuthCookieService());
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertFalse(interceptor.preHandle(new MockHttpServletRequest("GET", "/api/auth/me"), response, new Object()));
        assertEquals(401, response.getStatus());
    }
}
