package br.com.zentrix.web.config;

import java.util.List;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiAuthInterceptor apiAuthInterceptor;
    private final String[] allowedOrigins;

    public WebConfig(ApiAuthInterceptor apiAuthInterceptor, Environment environment) {
        this.apiAuthInterceptor = apiAuthInterceptor;
        List<String> origins = Binder.get(environment)
                .bind("zentrix.cors.allowed-origins", Bindable.listOf(String.class))
                .orElse(List.of("http://localhost:5500", "http://127.0.0.1:5500", "null"));
        this.allowedOrigins = origins.toArray(String[]::new);
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("Content-Type", "Authorization", "X-Zentrix-Sync-Key")
                .exposedHeaders("Authorization");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(apiAuthInterceptor)
                .addPathPatterns(
                        "/api/dashboard",
                        "/api/sales",
                        "/api/products",
                        "/api/cash-sessions",
                        "/api/stock/**",
                        "/api/audit",
                        "/api/backups",
                        "/api/clients",
                        "/api/employees",
                        "/api/finance",
                        "/api/reports",
                        "/api/settings"
                );
    }
}
