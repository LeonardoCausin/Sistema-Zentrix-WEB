package br.com.zentrix.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {
    private final String csp;

    public SecurityHeadersFilter(Environment environment) {
        this.csp = buildCsp(environment);
    }

    SecurityHeadersFilter() {
        this.csp = buildCsp(null);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        response.setHeader("Content-Security-Policy", csp);
        if (request.isSecure()) {
            response.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");
        }
        if (request.getRequestURI() != null && request.getRequestURI().startsWith("/api/")) {
            response.setHeader("Cache-Control", "no-store");
            response.setHeader("Pragma", "no-cache");
        }
        filterChain.doFilter(request, response);
    }

    private static String buildCsp(Environment environment) {
        return String.join("; ",
                "default-src 'self'",
                "base-uri 'self'",
                "object-src 'none'",
                "frame-ancestors 'none'",
                "img-src 'self' data: blob:",
                "style-src 'self' 'unsafe-inline'",
                "script-src 'self' 'unsafe-inline'",
                "connect-src " + String.join(" ", connectSources(environment))
        );
    }

    private static Set<String> connectSources(Environment environment) {
        Set<String> sources = new LinkedHashSet<>();
        sources.add("'self'");
        sources.add("http://localhost:8080");
        sources.add("http://127.0.0.1:8080");

        if (environment == null) {
            return sources;
        }

        addCsvValues(sources, environment.getProperty("zentrix.security.csp-connect-src", ""));
        addCsvValues(sources, environment.getProperty("zentrix.cors.allowed-origins-csv", ""));
        Binder.get(environment)
                .bind("zentrix.cors.allowed-origins", Bindable.listOf(String.class))
                .ifBound(origins -> origins.forEach(origin -> addConnectSource(sources, origin)));
        return sources;
    }

    private static void addCsvValues(Set<String> sources, String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String item : csv.split(",")) {
            addConnectSource(sources, item);
        }
    }

    private static void addConnectSource(Set<String> sources, String value) {
        if (value == null || value.isBlank() || "null".equals(value.trim())) {
            return;
        }
        String source = value.trim();
        sources.add(source);
        addApiPortSource(sources, source);
    }

    private static void addApiPortSource(Set<String> sources, String source) {
        try {
            URI uri = new URI(source);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return;
            }
            sources.add(uri.getScheme() + "://" + uri.getHost() + ":8080");
        } catch (URISyntaxException error) {
            // Ignora entradas CSP especificas como 'self' ou dominios sem URI absoluta.
        }
    }
}
