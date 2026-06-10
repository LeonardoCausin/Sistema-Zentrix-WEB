package br.com.zentrix.web.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ApiAuthInterceptor apiAuthInterceptor;

    @Value("${zentrix.cors.allowed-origins:*}")
    private String[] allowedOrigins;

    public WebConfig(ApiAuthInterceptor apiAuthInterceptor) {
        this.apiAuthInterceptor = apiAuthInterceptor;
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
                        "/api/backups"
                );
    }
}
