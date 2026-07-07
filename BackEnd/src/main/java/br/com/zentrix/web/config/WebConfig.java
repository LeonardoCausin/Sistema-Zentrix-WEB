package br.com.zentrix.web.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiAuthInterceptor apiAuthInterceptor;
    private final RateLimitInterceptor rateLimitInterceptor;
    private final String[] allowedOrigins;

    public WebConfig(ApiAuthInterceptor apiAuthInterceptor, RateLimitInterceptor rateLimitInterceptor, Environment environment) {
        this.apiAuthInterceptor = apiAuthInterceptor;
        this.rateLimitInterceptor = rateLimitInterceptor;
        boolean allowNullOrigin = Boolean.parseBoolean(environment.getProperty("zentrix.cors.allow-null-origin", "false"));
        List<String> configuredOrigins = Binder.get(environment)
                .bind("zentrix.cors.allowed-origins", Bindable.listOf(String.class))
                .orElse(List.of("http://localhost:5500", "http://127.0.0.1:5500"));
        List<String> origins = new ArrayList<>(configuredOrigins);
        addCsvValues(origins, environment.getProperty("zentrix.cors.allowed-origins-csv", ""));
        this.allowedOrigins = distinctNonBlank(origins, allowNullOrigin).toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-Zentrix-Sync-Key", "X-Request-Id")
                .exposedHeaders("Authorization", "X-Request-Id")
                .allowCredentials(true);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/FrontEnd/**")
                .addResourceLocations("file:../FrontEnd/", "file:FrontEnd/");
        registry.addResourceHandler("/index.html")
                .addResourceLocations("file:../", "file:./");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/index.html");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
        registry.addInterceptor(apiAuthInterceptor)
                .addPathPatterns(
                        "/api/auth/me",
                        "/api/admin/**",
                        "/api/dashboard",
                        "/api/sales",
                        "/api/sales/**",
                        "/api/products",
                        "/api/cash/current",
                        "/api/cash-sessions",
                        "/api/cash-sessions/**",
                        "/api/stock/**",
                        "/api/audit",
                        "/api/backups",
                        "/api/backups/**",
                        "/api/clients",
                        "/api/employees",
                        "/api/employees/**",
                        "/api/finance",
                        "/api/finance/**",
                        "/api/reports",
                        "/api/reports/**",
                        "/api/settings",
                        "/api/stores",
                        "/api/alerts",
                        "/api/observability",
                        "/api/sync/monitor",
                        "/api/sync/outbox/**",
                        "/api/license",
                        "/api/devices"
                );
    }

    private static void addCsvValues(List<String> values, String csv) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String item : csv.split(",")) {
            values.add(item);
        }
    }

    private static Set<String> distinctNonBlank(List<String> values, boolean allowNullOrigin) {
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                String origin = value.trim();
                if ("null".equals(origin) && !allowNullOrigin) {
                    continue;
                }
                result.add(origin);
            }
        }
        return result;
    }
}
