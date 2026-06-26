package br.com.zentrix.web.config;

import br.com.zentrix.web.service.AuthTokenService;
import br.com.zentrix.web.service.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    private final AuthTokenService authTokenService;

    public ApiAuthInterceptor(AuthTokenService authTokenService) {
        this.authTokenService = authTokenService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Token de acesso ausente");
            return false;
        }

        String token = authorization.substring("Bearer ".length()).trim();
        var session = authTokenService.validate(token);
        if (session.isEmpty()) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Token de acesso inválido ou expirado");
            return false;
        }
        AuthContext.set(session.get());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }
}
