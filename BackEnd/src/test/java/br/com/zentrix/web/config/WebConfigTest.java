package br.com.zentrix.web.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import br.com.zentrix.web.service.AuthCookieService;
import br.com.zentrix.web.service.AuthTokenService;
import java.lang.reflect.Field;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class WebConfigTest {

    @Test
    void includesProductionDomainAndTunnelPatternsInCors() throws Exception {
        WebConfig config = new WebConfig(
                new ApiAuthInterceptor(new AuthTokenService(), new AuthCookieService()),
                new RateLimitInterceptor(),
                new MockEnvironment()
        );

        String[] patterns = allowedOriginPatterns(config);

        assertTrue(Arrays.asList(patterns).contains("https://pdv.zentrixsystems.com.br"));
        assertTrue(Arrays.asList(patterns).contains("https://www.pdv.zentrixsystems.com.br"));
        assertTrue(Arrays.asList(patterns).contains("http://localhost:*"));
        assertTrue(Arrays.asList(patterns).contains("https://*.trycloudflare.com"));
    }

    private String[] allowedOriginPatterns(WebConfig config) throws Exception {
        Field field = WebConfig.class.getDeclaredField("allowedOriginPatterns");
        field.setAccessible(true);
        return (String[]) field.get(config);
    }
}
