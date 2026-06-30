package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AuthTokenServiceTest {

    @Test
    void revokedTokenIsRejected() {
        AuthTokenService service = new AuthTokenService();
        String token = service.issue("admin", "Administrador", "ADMIN", "tenant-1");

        assertTrue(service.validate(token).isPresent());

        service.revoke(token);

        assertFalse(service.validate(token).isPresent());
    }

    @Test
    void canRevokeAllTokensFromUser() {
        AuthTokenService service = new AuthTokenService();
        String first = service.issue("admin", "Administrador", "ADMIN", "tenant-1");
        String second = service.issue("ADMIN", "Administrador", "ADMIN", "tenant-1");
        String other = service.issue("operador", "Operador", "OPERATOR", "tenant-1");

        service.revokeUser("admin");

        assertFalse(service.validate(first).isPresent());
        assertFalse(service.validate(second).isPresent());
        assertTrue(service.validate(other).isPresent());
    }
}
