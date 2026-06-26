package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.SyncPushRequest;
import br.com.zentrix.web.service.SyncIngestService;
import br.com.zentrix.web.service.SyncKeyService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/sync")
public class SyncController {

    private final SyncIngestService syncIngestService;
    private final SyncKeyService syncKeyService;

    public SyncController(SyncIngestService syncIngestService, SyncKeyService syncKeyService) {
        this.syncIngestService = syncIngestService;
        this.syncKeyService = syncKeyService;
    }

    @PostMapping("/push")
    public Map<String, Object> push(
            @RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey,
            @Valid @RequestBody SyncPushRequest request
    ) {
        syncKeyService.require(syncKey);
        try {
            return syncIngestService.ingest(request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Banco web indisponível para sincronização", e);
        }
    }

    @GetMapping("/status")
    public Map<String, Object> status(
            @RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey,
            @RequestParam(required = false) String tenantId,
            @RequestParam(required = false) String storeId,
            @RequestParam(required = false) String sourceId
    ) {
        syncKeyService.require(syncKey);
        try {
            return syncIngestService.lastStatus(tenantId, storeId, sourceId);
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Banco web indisponível", e);
        }
    }

}
