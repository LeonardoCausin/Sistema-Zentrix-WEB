package br.com.zentrix.web.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SyncKeyService {
    @Value("${zentrix.sync.api-key:}")
    private String syncApiKey;

    public void require(String syncKey) {
        if (syncApiKey == null || syncApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure ZENTRIX_SYNC_KEY antes de sincronizar");
        }
        byte[] expected = syncApiKey.trim().getBytes(StandardCharsets.UTF_8);
        byte[] received = syncKey == null ? new byte[0] : syncKey.trim().getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, received)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chave de sincronização inválida");
        }
    }
}
