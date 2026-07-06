package br.com.zentrix.web.controller;

import br.com.zentrix.web.dto.SyncPushRequest;
import br.com.zentrix.web.dto.SyncAckRequest;
import br.com.zentrix.web.service.SyncIngestService;
import br.com.zentrix.web.service.SyncKeyService;
import br.com.zentrix.web.service.WebChangeOutboxService;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/sync")
public class SyncController {
    private static final Logger log = LoggerFactory.getLogger(SyncController.class);

    private final SyncIngestService syncIngestService;
    private final SyncKeyService syncKeyService;
    private final WebChangeOutboxService webChangeOutboxService;

    public SyncController(
            SyncIngestService syncIngestService,
            SyncKeyService syncKeyService,
            WebChangeOutboxService webChangeOutboxService
    ) {
        this.syncIngestService = syncIngestService;
        this.syncKeyService = syncKeyService;
        this.webChangeOutboxService = webChangeOutboxService;
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

    @GetMapping("/pull")
    public Map<String, Object> pull(
            @RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey,
            @RequestParam String tenantId,
            @RequestParam String storeId,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String deviceId,
            @RequestParam(defaultValue = "0") long afterId,
            @RequestParam(defaultValue = "100") int limit
    ) {
        syncKeyService.require(syncKey);
        try {
            return webChangeOutboxService.pull(tenantId, storeId, sourceId, deviceId, afterId, limit);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Banco web indisponivel para entregar mudancas", e);
        } catch (RuntimeException e) {
            log.error("Falha inesperada no pull Web -> PDV tenant={} store={} source={} device={} afterId={}",
                    tenantId, storeId, sourceId, deviceId, afterId, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Servico de sincronizacao temporariamente indisponivel", e);
        }
    }

    @PostMapping("/ack")
    public Map<String, Object> ack(
            @RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey,
            @RequestParam String tenantId,
            @RequestParam String storeId,
            @RequestParam(required = false) String sourceId,
            @RequestParam(required = false) String deviceId,
            @Valid @RequestBody SyncAckRequest request
    ) {
        syncKeyService.require(syncKey);
        try {
            return webChangeOutboxService.ack(tenantId, storeId, sourceId, deviceId, request);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (DataAccessException | IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Banco web indisponivel para confirmar mudancas", e);
        }
    }

    @GetMapping("/contract")
    public Map<String, Object> contract(
            @RequestHeader(value = "X-Zentrix-Sync-Key", required = false) String syncKey
    ) {
        syncKeyService.require(syncKey);
        return webChangeOutboxService.contract();
    }

}
