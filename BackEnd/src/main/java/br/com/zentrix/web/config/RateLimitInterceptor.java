package br.com.zentrix.web.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {
    private final Map<String, Window> windows = new ConcurrentHashMap<>();
    private final Clock clock;
    private boolean enabled = true;
    private int defaultLimit = 240;
    private long defaultWindowSeconds = 60;
    private int authLimit = 30;
    private long authWindowSeconds = 300;
    private int syncLimit = 120;
    private long syncWindowSeconds = 60;
    private boolean trustProxyHeaders = false;

    public RateLimitInterceptor() {
        this(Clock.systemUTC());
    }

    RateLimitInterceptor(Clock clock) {
        this.clock = clock;
    }

    @Value("${zentrix.rate-limit.enabled:true}")
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    @Value("${zentrix.rate-limit.default-limit:240}")
    public void setDefaultLimit(int defaultLimit) {
        this.defaultLimit = Math.max(1, defaultLimit);
    }

    @Value("${zentrix.rate-limit.default-window-seconds:60}")
    public void setDefaultWindowSeconds(long defaultWindowSeconds) {
        this.defaultWindowSeconds = Math.max(1, defaultWindowSeconds);
    }

    @Value("${zentrix.rate-limit.auth-limit:30}")
    public void setAuthLimit(int authLimit) {
        this.authLimit = Math.max(1, authLimit);
    }

    @Value("${zentrix.rate-limit.auth-window-seconds:300}")
    public void setAuthWindowSeconds(long authWindowSeconds) {
        this.authWindowSeconds = Math.max(1, authWindowSeconds);
    }

    @Value("${zentrix.rate-limit.sync-limit:120}")
    public void setSyncLimit(int syncLimit) {
        this.syncLimit = Math.max(1, syncLimit);
    }

    @Value("${zentrix.rate-limit.sync-window-seconds:60}")
    public void setSyncWindowSeconds(long syncWindowSeconds) {
        this.syncWindowSeconds = Math.max(1, syncWindowSeconds);
    }

    @Value("${zentrix.rate-limit.trust-proxy-headers:false}")
    public void setTrustProxyHeaders(boolean trustProxyHeaders) {
        this.trustProxyHeaders = trustProxyHeaders;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled || "OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        Policy policy = policyFor(request.getRequestURI());
        long now = clock.millis();
        String key = policy.name() + ":" + clientId(request);
        Window window = windows.compute(key, (ignored, current) -> nextWindow(current, now, policy.windowMillis()));
        int remaining = Math.max(0, policy.limit() - window.count());
        response.setHeader("X-RateLimit-Limit", String.valueOf(policy.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        response.setHeader("X-RateLimit-Reset", String.valueOf(window.resetAt()));
        if (window.count() > policy.limit()) {
            response.sendError(HttpStatus.TOO_MANY_REQUESTS.value(), "Muitas requisicoes. Tente novamente em instantes.");
            return false;
        }
        prune(now);
        return true;
    }

    private Window nextWindow(Window current, long now, long windowMillis) {
        if (current == null || now >= current.resetAt()) {
            return new Window(1, now + windowMillis);
        }
        return new Window(current.count() + 1, current.resetAt());
    }

    private Policy policyFor(String uri) {
        String path = uri == null ? "" : uri;
        if (path.startsWith("/api/auth/login") || path.startsWith("/api/pdv/activation")) {
            return new Policy("auth", authLimit, authWindowSeconds * 1000L);
        }
        if (path.startsWith("/api/sync")) {
            return new Policy("sync", syncLimit, syncWindowSeconds * 1000L);
        }
        return new Policy("api", defaultLimit, defaultWindowSeconds * 1000L);
    }

    private String clientId(HttpServletRequest request) {
        if (trustProxyHeaders) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isBlank()) {
                return forwarded.split(",")[0].trim();
            }
            String realIp = request.getHeader("X-Real-IP");
            if (realIp != null && !realIp.isBlank()) {
                return realIp.trim();
            }
        }
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    private void prune(long now) {
        if (windows.size() < 4096) {
            return;
        }
        windows.entrySet().removeIf(entry -> now >= entry.getValue().resetAt());
    }

    private record Policy(String name, int limit, long windowMillis) {
    }

    private record Window(int count, long resetAt) {
    }
}
