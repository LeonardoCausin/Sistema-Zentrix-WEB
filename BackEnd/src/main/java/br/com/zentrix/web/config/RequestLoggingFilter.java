package br.com.zentrix.web.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestLoggingFilter extends OncePerRequestFilter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RequestLoggingFilter.class);
    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String requestId = requestId(request);
        long start = System.currentTimeMillis();
        response.setHeader(REQUEST_ID_HEADER, requestId);
        MDC.put("requestId", requestId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = System.currentTimeMillis() - start;
            if (shouldLog(request)) {
                String message = "request_id={} method={} path={} status={} duration_ms={} remote_addr={}";
                Object[] args = {
                        requestId,
                        request.getMethod(),
                        request.getRequestURI(),
                        response.getStatus(),
                        durationMs,
                        request.getRemoteAddr()
                };
                if (response.getStatus() >= 500 || durationMs >= 1000) {
                    LOGGER.warn(message, args);
                } else {
                    LOGGER.info(message, args);
                }
            }
            MDC.remove("requestId");
        }
    }

    private static boolean shouldLog(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path != null && path.startsWith("/api/");
    }

    private static String requestId(HttpServletRequest request) {
        String value = request.getHeader(REQUEST_ID_HEADER);
        if (value == null || value.isBlank() || value.length() > 80) {
            return UUID.randomUUID().toString();
        }
        return value.trim();
    }
}
