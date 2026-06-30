package br.com.zentrix.web.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SetupKeyService {
    @Value("${zentrix.setup.api-key:}")
    private String setupApiKey;

    public void require(String setupKey) {
        if (setupApiKey == null || setupApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure ZENTRIX_SETUP_KEY antes de usar a ativacao remota");
        }
        byte[] expected = setupApiKey.trim().getBytes(StandardCharsets.UTF_8);
        byte[] received = setupKey == null ? new byte[0] : setupKey.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, received)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chave de setup invalida");
        }
    }
}
