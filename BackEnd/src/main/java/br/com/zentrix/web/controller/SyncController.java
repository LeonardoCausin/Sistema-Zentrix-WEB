package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.SyncPushRequest;
import br.com.zentrix.web.service.SyncIngestService;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncIngestService syncIngestService;

    @Value("${zentrix.sync.api-key:}")
    private String syncApiKey;

    public SyncController(SyncIngestService syncIngestService) {
        this.syncIngestService = syncIngestService;
    }

    @PostMapping("/push")
    public Map<String, Object> push(
            @RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey,
            @Valid @RequestBody SyncPushRequest request
    ) {
        requireSyncKey(syncKey);
        try {
            return syncIngestService.ingest(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Banco web indisponivel para sincronizacao", e);
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(@RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey) {
        requireSyncKey(syncKey);
        try {
            return syncIngestService.lastStatus();
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Banco web indisponivel", e);
        }
    }

    private void requireSyncKey(String syncKey) {
        if (syncApiKey == null || syncApiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Configure ZENTRIX_SYNC_KEY antes de sincronizar");
        }
        byte[] expected = syncApiKey.getBytes(StandardCharsets.UTF_8);
        byte[] received = syncKey == null ? new byte[0] : syncKey.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, received)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Chave de sincronizacao invalida");
        }
    }
}
