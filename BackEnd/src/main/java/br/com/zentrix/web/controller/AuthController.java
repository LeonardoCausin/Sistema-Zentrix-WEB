package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.LoginRequest;
import br.com.zentrix.web.dto.LoginResponse;
import br.com.zentrix.web.service.AuthContext;
import br.com.zentrix.web.service.AuthService;
import br.com.zentrix.web.service.AuthTokenService;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
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

    public AuthController(AuthService authService, AuthTokenService authTokenService) {
        this.authService = authService;
        this.authTokenService = authTokenService;
    }

    @PostMapping("/login")
    @ResponseStatus(HttpStatus.OK)
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    public Map<String, Object> me() {
        var session = AuthContext.current().orElseThrow();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", session.username());
        response.put("displayName", session.displayName());
        response.put("role", session.role());
        response.put("tenantId", session.tenantId());
        response.put("issuedAt", session.issuedAt().toString());
        response.put("expiresAt", session.expiresAt().toString());
        return response;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            authTokenService.revoke(authorization.substring("Bearer ".length()).trim());
        }
    }
}
