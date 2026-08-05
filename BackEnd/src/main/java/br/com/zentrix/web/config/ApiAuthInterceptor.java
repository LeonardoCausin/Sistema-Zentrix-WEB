package br.com.zentrix.web.config;

import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.AuthCookieService;
import br.com.zentrix.web.service.AuthTokenService;
import br.com.zentrix.web.service.LicenseAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class ApiAuthInterceptor implements HandlerInterceptor {

    private final AuthTokenService authTokenService;
    private final AuthCookieService authCookieService;
    private final LicenseAccessService licenseAccessService;

    @Autowired
    public ApiAuthInterceptor(AuthTokenService authTokenService, AuthCookieService authCookieService, LicenseAccessService licenseAccessService) {
        this.authTokenService = authTokenService;
        this.authCookieService = authCookieService;
        this.licenseAccessService = licenseAccessService;
    }

    public ApiAuthInterceptor(AuthTokenService authTokenService, AuthCookieService authCookieService) {
        this.authTokenService = authTokenService;
        this.authCookieService = authCookieService;
        this.licenseAccessService = null;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = tokenFromRequest(request);
        if (token == null || token.isBlank()) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Sua sessão expirou. Entre novamente.");
            return false;
        }

        var session = authTokenService.validate(token);
        if (session.isEmpty()) {
            response.sendError(HttpStatus.UNAUTHORIZED.value(), "Sua sessão expirou. Entre novamente.");
            return false;
        }
        AuthContext.set(session.get());
        if (licenseAccessService != null) {
            try {
                licenseAccessService.requireActive(session.get().tenantId(), request.getRequestURI());
            } catch (ResponseStatusException e) {
                AuthContext.clear();
                response.sendError(e.getStatusCode().value(), e.getReason());
                return false;
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private String tokenFromRequest(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            return authorization.substring("Bearer ".length()).trim();
        }
        return authCookieService.readToken(request);
    }
}
