package br.com.zentrix.web.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.zentrix.web.service.AuthCookieService;
import br.com.zentrix.web.service.AuthTokenService;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WebConfigTest {

    @Test
    void includesOnlyOfficialProductionAndLocalDevelopmentPatternsInCors() throws Exception {
        WebConfig config = new WebConfig(
                new ApiAuthInterceptor(new AuthTokenService(), new AuthCookieService()),
                new RateLimitInterceptor(),
                new MockEnvironment()
        );

        String[] patterns = allowedOriginPatterns(config);

        var allowed = Arrays.asList(patterns);

        assertTrue(allowed.contains("https://pdv.zentrixsystems.com.br"));
        assertTrue(allowed.contains("https://www.pdv.zentrixsystems.com.br"));
        assertTrue(allowed.contains("http://localhost:*"));
        assertTrue(allowed.contains("http://127.0.0.1:*"));
        assertFalse(allowed.contains("https://*.zentrixsystems.com.br"));
        assertFalse(allowed.contains("https://*.trycloudflare.com"));
        assertFalse(allowed.contains("https://*.ngrok-free.app"));
        assertFalse(allowed.contains("https://*.ngrok.io"));
    }

    private String[] allowedOriginPatterns(WebConfig config) throws Exception {
        Field field = WebConfig.class.getDeclaredField("allowedOriginPatterns");
        field.setAccessible(true);
        return (String[]) field.get(config);
    }
}
