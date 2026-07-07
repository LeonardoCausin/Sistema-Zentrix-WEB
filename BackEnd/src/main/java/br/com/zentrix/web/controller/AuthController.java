package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.LoginRequest;
import br.com.zentrix.web.dto.LoginResponse;
import br.com.zentrix.web.service.AuthCookieService;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.AuthService;
import br.com.zentrix.web.service.AuthTokenService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthTokenService authTokenService;
    private final AuthCookieService authCookieService;

    public AuthController(AuthService authService, AuthTokenService authTokenService, AuthCookieService authCookieService) {
        this.authService = authService;
        this.authTokenService = authTokenService;
        this.authCookieService = authCookieService;
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    public LoginResponse login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        LoginResponse login = authService.login(request);
        authCookieService.writeToken(response, login.token());
        return login;
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var session = AuthContext.current().orElseThrow();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", session.username());
        response.put("displayName", session.displayName());
        response.put("role", session.role());
        response.put("tenantId", session.tenantId());
        response.put("storeId", session.storeId());
        response.put("sourceId", session.sourceId());
        response.put("permissions", session.permissions());
        response.put("issuedAt", session.issuedAt().toString());
        response.put("expiresAt", session.expiresAt().toString());
        return response;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            HttpServletRequest request,
            HttpServletResponse response
    ) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authTokenService.revoke(authorization.substring("Bearer ".length()).trim());
        }
        authTokenService.revoke(authCookieService.readToken(request));
        authCookieService.clearToken(response);
    }
}
