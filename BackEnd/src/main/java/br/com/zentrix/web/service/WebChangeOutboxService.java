package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.SyncAckRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebChangeOutboxService {
    private static final String CONTRACT_VERSION = "2026-07-02";
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_ERROR_RETRIES = 3;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final List<String> SUPPORTED_ENTITY_TYPES = List.of(
            "PRODUCT",
            "CLIENT",
            "USER",
            "EMPLOYEE",
            "CASH_SESSION",
            "CASH_MOVEMENT",
            "SALE",
            "SALE_CANCELLATION",
            "STOCK_MOVEMENT",
            "FINANCIAL_ENTRY"
    );
    private static final List<Map<String, Object>> UNSUPPORTED_ENTITY_TYPES = List.of(
            Map.of("entityType", "SUPPLIER", "reason", "Fornecedores ainda não são enviados do painel para o PDV nesta versão."),
            Map.of("entityType", "COMANDA", "reason", "Comandas continuam originadas no PDV e entram no Web via push."),
            Map.of("entityType", "AUDIT_LOG", "reason", "Auditoria e histórico operacional não são reenviados ao PDV.")
    );

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final ObjectMapper objectMapper;
    private boolean requireKnownDeviceScope = true;

    public WebChangeOutboxService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            ObjectMapper objectMapper
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.objectMapper = objectMapper;
    }

    @Value("${zentrix.sync.require-known-device-scope:true}")
    public void setRequireKnownDeviceScope(boolean requireKnownDeviceScope) {
        this.requireKnownDeviceScope = requireKnownDeviceScope;
    }

    public long enqueue(String tenantId, String storeId, String entityType, String entityId, String operation, Map<String, Object> payload) {
        return enqueue(tenantId, storeId, null, null, entityType, entityId, operation, payload);
    }

    public long enqueue(String tenantId, String storeId, String targetSourceId, String targetDeviceId, String entityType, String entityId, String operation, Map<String, Object> payload) {
        String safeTenant = required(tenantId, "tenantId");
        String safeStore = required(storeId, "storeId");
        String safeEntityType = required(entityType, "entityType").toUpperCase();
        String safeEntityId = required(entityId, "entityId");
        String safeOperation = required(operation, "operation").toUpperCase();
        String safeTargetSource = optional(targetSourceId, knownStoreSourceId(safeTenant, safeStore));
        String safeTargetDevice = optional(targetDeviceId, null);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("contractVersion", CONTRACT_VERSION);
        envelope.put("tenantId", safeTenant);
        envelope.put("storeId", safeStore);
        envelope.put("sourceId", "WEB");
        envelope.put("origin", "WEB");
        envelope.put("targetSourceId", safeTargetSource);
        envelope.put("targetDeviceId", safeTargetDevice);
        envelope.put("entityType", safeEntityType);
        envelope.put("entityId", safeEntityId);
        envelope.put("operation", safeOperation);
        envelope.put("generatedAt", OffsetDateTime.now().toString());
        envelope.put("payload", payload == null ? Map.of() : payload);

        jdbcTemplate.update("""
                INSERT INTO web_change_outbox
                    (tenant_id, store_id, source_id, target_source_id, target_device_id, entity_type, entity_id,
                     operation, contract_version, payload_json, status, next_attempt_at)
                VALUES (?, ?, 'WEB', ?, ?, ?, ?, ?, ?, ?, 'PENDING', NULL)
                """, safeTenant, safeStore, safeTargetSource, safeTargetDevice, safeEntityType, safeEntityId, safeOperation, CONTRACT_VERSION, toJson(envelope));
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return id == null ? -1L : id;
    }

    public Map<String, Object> pull(String tenantId, String storeId, long afterId, int limit) {
        return pull(tenantId, storeId, null, null, afterId, limit);
    }

    public Map<String, Object> contract() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("contractVersion", CONTRACT_VERSION);
        response.put("supportedEntityTypes", SUPPORTED_ENTITY_TYPES);
        response.put("unsupportedEntityTypes", UNSUPPORTED_ENTITY_TYPES);
        response.put("statusPolicy", statusPolicy());
        response.put("requiredScope", List.of("tenantId", "storeId"));
        response.put("deviceScopePolicy", "O PDV precisa estar identificado quando a loja já possui equipamento cadastrado.");
        response.put("optionalScope", List.of("sourceId", "deviceId"));
        response.put("payloadShape", Map.of(
                "table", "Nome da tabela destino em snake_case",
                "record", "Registro com nomes de colunas do banco",
                "primaryKey", "Mapa da chave primaria do registro",
                "revision", "updated_at, date_time ou created_at quando existir"
        ));
        response.put("integrity", Map.of(
                "payloadHash", "SHA-256 do payload_json enviado em cada item.",
                "batchHash", "SHA-256 calculado com id:payloadHash de todos os itens do lote, na ordem recebida."
        ));
        return response;
    }

    public Map<String, Object> monitor(String tenantId, String storeId) {
        initializer.ensureReady();
        String safeTenant = required(tenantId, "tenantId");
        String safeStore = optional(storeId, null);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("contractVersion", CONTRACT_VERSION);
        response.put("tenantId", safeTenant);
        response.put("storeId", safeStore == null ? "all" : safeStore);
        response.put("serverTime", OffsetDateTime.now().toString());
        response.put("summary", outboxSummary(safeTenant, safeStore));
        response.put("oldestPending", oldestOutbox(safeTenant, safeStore));
        response.put("recentErrors", recentOutboxErrors(safeTenant, safeStore));
        response.put("recentSyncRuns", recentSyncRuns(safeTenant, safeStore));
        response.put("deadLetterCount", outboxCount(safeTenant, safeStore, "DEAD"));
        response.put("retryableErrorCount", outboxCount(safeTenant, safeStore, "ERROR"));
        response.put("pendingCount", outboxCount(safeTenant, safeStore, "PENDING"));
        response.put("deliveredCount", outboxCount(safeTenant, safeStore, "DELIVERED"));
        return response;
    }

    public Map<String, Object> retryOutboxItem(String tenantId, String storeId, long id, String reason) {
        initializer.ensureReady();
        String safeTenant = required(tenantId, "tenantId");
        String safeStore = optional(storeId, null);
        List<Object> args = new ArrayList<>();
        args.add(cleanError(reason == null || reason.isBlank() ? "Reenvio solicitado pelo painel" : reason));
        args.add(safeTenant);
        String scope = storePredicate(safeStore, args);
        args.add(id);
        int updated = jdbcTemplate.update("""
                UPDATE web_change_outbox
                SET status = 'PENDING',
                    next_attempt_at = NULL,
                    delivered_at = NULL,
                    dead_letter_at = NULL,
                    last_error = ?
                WHERE tenant_id = ?
                %s
                  AND id = ?
                  AND status IN ('ERROR', 'DEAD', 'DELIVERED')
                """.formatted(scope), args.toArray());
        return Map.of("status", updated > 0 ? "RETRY_SCHEDULED" : "NOT_CHANGED", "count", updated, "id", id);
    }

    public Map<String, Object> deadLetterOutboxItem(String tenantId, String storeId, long id, String reason) {
        initializer.ensureReady();
        String safeTenant = required(tenantId, "tenantId");
        String safeStore = optional(storeId, null);
        List<Object> args = new ArrayList<>();
        args.add(cleanError(reason == null || reason.isBlank() ? "Item pausado pelo painel para não travar os próximos envios" : reason));
        args.add(safeTenant);
        String scope = storePredicate(safeStore, args);
        args.add(id);
        int updated = jdbcTemplate.update("""
                UPDATE web_change_outbox
                SET status = 'DEAD',
                    dead_letter_at = CURRENT_TIMESTAMP,
                    next_attempt_at = NULL,
                    acknowledged_at = NULL,
                    last_error = ?
                WHERE tenant_id = ?
                %s
                  AND id = ?
                  AND status <> 'ACKED'
                """.formatted(scope), args.toArray());
        return Map.of("status", updated > 0 ? "DEAD_LETTERED" : "NOT_CHANGED", "count", updated, "id", id);
    }

    public Map<String, Object> pull(String tenantId, String storeId, String sourceId, String deviceId, long afterId, int limit) {
        initializer.ensureReady();
        String safeTenant = required(tenantId, "tenantId");
        String safeStore = required(storeId, "storeId");
        String safeSource = optional(sourceId, null);
        String safeDevice = optional(deviceId, null);
        validateKnownSyncScope(safeTenant, safeStore, safeSource, safeDevice);
        touchDevicePresence(safeTenant, safeStore, safeSource, safeDevice);
        int safeLimit = safeLimit(limit);
        long effectiveAfterId = effectiveAfterId(safeTenant, safeStore, safeSource, safeDevice, afterId);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tenant_id, store_id, source_id, target_source_id, target_device_id, entity_type, entity_id,
                       operation, contract_version, payload_json, status, attempts, error_count, last_error,
                       created_at, delivered_at, next_attempt_at
                FROM web_change_outbox
                WHERE tenant_id = ?
                  AND store_id = ?
                  AND id > ?
                  AND (
                        status IN ('PENDING', 'DELIVERED')
                        OR (status = 'ERROR' AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP))
                  )
                  AND (target_source_id IS NULL OR ? IS NULL OR target_source_id = ?)
                  AND (target_device_id IS NULL OR ? IS NULL OR target_device_id = ?)
                ORDER BY id
                LIMIT ?
                """, safeTenant, safeStore, effectiveAfterId,
                safeSource, safeSource, safeDevice, safeDevice, safeLimit + 1);
        boolean hasMore = rows.size() > safeLimit;
        List<Map<String, Object>> page = hasMore ? rows.subList(0, safeLimit) : rows;
        markDelivered(safeTenant, safeStore, page);

        List<Map<String, Object>> changes = new ArrayList<>();
        List<String> batchParts = new ArrayList<>();
        long nextCursor = Math.max(0L, afterId);
        for (Map<String, Object> row : page) {
            long id = ((Number) row.get("id")).longValue();
            String payloadJson = String.valueOf(row.get("payload_json"));
            String payloadHash = sha256(payloadJson);
            nextCursor = id;
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("id", id);
            change.put("contractVersion", row.get("contract_version"));
            change.put("tenantId", row.get("tenant_id"));
            change.put("storeId", row.get("store_id"));
            change.put("sourceId", row.get("source_id"));
            change.put("targetSourceId", row.get("target_source_id"));
            change.put("targetDeviceId", row.get("target_device_id"));
            change.put("entityType", row.get("entity_type"));
            change.put("entityId", row.get("entity_id"));
            change.put("operation", row.get("operation"));
            change.put("status", row.get("status"));
            change.put("attempts", row.get("attempts"));
            change.put("errorCount", row.get("error_count"));
            change.put("lastError", row.get("last_error"));
            change.put("createdAt", row.get("created_at"));
            change.put("deliveredAt", row.get("delivered_at"));
            change.put("nextAttemptAt", row.get("next_attempt_at"));
            change.put("payload", fromJson(row.get("payload_json")));
            change.put("payloadHash", payloadHash);
            changes.add(change);
            batchParts.add(id + ":" + payloadHash);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("contractVersion", CONTRACT_VERSION);
        response.put("tenantId", safeTenant);
        response.put("storeId", safeStore);
        response.put("sourceId", safeSource);
        response.put("deviceId", safeDevice);
        response.put("afterId", Math.max(0L, afterId));
        response.put("effectiveAfterId", effectiveAfterId);
        response.put("nextCursor", nextCursor);
        response.put("hasMore", hasMore);
        response.put("count", changes.size());
        response.put("changes", changes);
        response.put("batchHash", sha256(String.join("|", batchParts)));
        response.put("supportedEntityTypes", SUPPORTED_ENTITY_TYPES);
        response.put("unsupportedEntityTypes", UNSUPPORTED_ENTITY_TYPES);
        response.put("statusPolicy", statusPolicy());
        response.put("serverTime", OffsetDateTime.now().toString());
        return response;
    }

    private void touchDevicePresence(String tenantId, String storeId, String sourceId, String deviceId) {
        String safeDevice = optional(deviceId, optional(sourceId, null));
        if (safeDevice == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO tenant_devices (tenant_id, store_id, id, name, source_id, status, last_seen_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE
                    source_id = COALESCE(VALUES(source_id), source_id),
                    status = 'ACTIVE',
                    last_seen_at = CURRENT_TIMESTAMP,
                    updated_at = CURRENT_TIMESTAMP
                """, tenantId, storeId, safeDevice, safeDevice, sourceId);
    }

    private long effectiveAfterId(String tenantId, String storeId, String sourceId, String deviceId, long afterId) {
        long requested = Math.max(0L, afterId);
        if (requested == 0L) {
            return 0L;
        }
        Integer newerCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM web_change_outbox
                WHERE tenant_id = ?
                  AND store_id = ?
                  AND id > ?
                  AND (
                        status IN ('PENDING', 'DELIVERED')
                        OR (status = 'ERROR' AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP))
                  )
                  AND (target_source_id IS NULL OR ? IS NULL OR target_source_id = ?)
                  AND (target_device_id IS NULL OR ? IS NULL OR target_device_id = ?)
                """, Integer.class, tenantId, storeId, requested, sourceId, sourceId, deviceId, deviceId);
        if (newerCount != null && newerCount > 0) {
            return requested;
        }
        Integer strandedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM web_change_outbox
                WHERE tenant_id = ?
                  AND store_id = ?
                  AND id <= ?
                  AND (
                        status IN ('PENDING', 'DELIVERED')
                        OR (status = 'ERROR' AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP))
                  )
                  AND (target_source_id IS NULL OR ? IS NULL OR target_source_id = ?)
                  AND (target_device_id IS NULL OR ? IS NULL OR target_device_id = ?)
                """, Integer.class, tenantId, storeId, requested, sourceId, sourceId, deviceId, deviceId);
        return strandedCount != null && strandedCount > 0 ? 0L : requested;
    }

    public Map<String, Object> ack(String tenantId, String storeId, SyncAckRequest request) {
        return ack(tenantId, storeId, null, null, request);
    }

    public Map<String, Object> ack(String tenantId, String storeId, String sourceId, String deviceId, SyncAckRequest request) {
        initializer.ensureReady();
        if (request == null || request.ids() == null || request.ids().isEmpty()) {
            throw new IllegalArgumentException("Informe ao menos um id para confirmar");
        }
        String safeTenant = required(tenantId, "tenantId");
        String safeStore = required(storeId, "storeId");
        String safeSource = optional(sourceId, null);
        String safeDevice = optional(deviceId, null);
        validateKnownSyncScope(safeTenant, safeStore, safeSource, safeDevice);
        touchDevicePresence(safeTenant, safeStore, safeSource, safeDevice);
        List<Long> ids = request.ids().stream()
                .filter(Objects::nonNull)
                .filter(id -> id > 0)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("IDs de confirmação inválidos");
        }
        String status = request.normalizedStatus();
        if (!status.equals("ACKED") && !status.equals("ERROR")) {
            throw new IllegalArgumentException("status deve ser ACKED ou ERROR");
        }
        if (status.equals("ACKED")) {
            return ackSuccess(safeTenant, safeStore, safeSource, safeDevice, ids);
        }
        return ackError(safeTenant, safeStore, safeSource, safeDevice, ids, cleanError(request.error()), request.shouldRetry());
    }

    private Map<String, Object> ackSuccess(String tenantId, String storeId, String sourceId, String deviceId, List<Long> ids) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(storeId);
        addTargetArgs(args, sourceId, deviceId);
        args.addAll(ids);
        int updated = jdbcTemplate.update("""
                UPDATE web_change_outbox
                SET status = 'ACKED',
                    acknowledged_at = CURRENT_TIMESTAMP,
                    last_error = NULL,
                    next_attempt_at = NULL,
                    dead_letter_at = NULL
                WHERE tenant_id = ?
                  AND store_id = ?
                  %s
                  AND id IN (%s)
                """.formatted(targetPredicate(sourceId, deviceId), placeholders(ids.size())), args.toArray());
        return Map.of("status", "ACKED", "count", updated);
    }

    private Map<String, Object> ackError(String tenantId, String storeId, String sourceId, String deviceId, List<Long> ids, String error, boolean retryable) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(storeId);
        addTargetArgs(args, sourceId, deviceId);
        args.addAll(ids);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, error_count
                FROM web_change_outbox
                WHERE tenant_id = ?
                  AND store_id = ?
                  %s
                  AND id IN (%s)
                """.formatted(targetPredicate(sourceId, deviceId), placeholders(ids.size())), args.toArray());
        int updated = 0;
        int dead = 0;
        Instant now = Instant.now();
        for (Map<String, Object> row : rows) {
            long id = ((Number) row.get("id")).longValue();
            int nextErrorCount = (row.get("error_count") instanceof Number number ? number.intValue() : 0) + 1;
            boolean terminal = !retryable || nextErrorCount >= MAX_ERROR_RETRIES;
            Timestamp nextAttempt = terminal ? null : Timestamp.from(now.plus(backoff(nextErrorCount)));
            updated += jdbcTemplate.update("""
                    UPDATE web_change_outbox
                    SET status = ?,
                        error_count = ?,
                        last_error = ?,
                        acknowledged_at = NULL,
                        next_attempt_at = ?,
                        dead_letter_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE dead_letter_at END
                    WHERE tenant_id = ? AND store_id = ? AND id = ?
                    """,
                    terminal ? "DEAD" : "ERROR",
                    nextErrorCount,
                    error,
                    nextAttempt,
                    terminal,
                    tenantId,
                    storeId,
                    id);
            if (terminal) {
                dead++;
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", dead > 0 ? "DEAD_OR_ERROR" : "ERROR");
        response.put("count", updated);
        response.put("deadLettered", dead);
        response.put("retryable", updated - dead);
        response.put("retryScheduled", updated - dead);
        return response;
    }

    private void markDelivered(String tenantId, String storeId, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return;
        }
        List<Long> ids = rows.stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .toList();
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(storeId);
        args.addAll(ids);
        jdbcTemplate.update("""
                UPDATE web_change_outbox
                SET status = 'DELIVERED',
                    attempts = attempts + 1,
                    delivered_at = CURRENT_TIMESTAMP,
                    next_attempt_at = NULL
                WHERE tenant_id = ? AND store_id = ? AND id IN (%s) AND status IN ('PENDING', 'DELIVERED', 'ERROR')
                """.formatted(placeholders(ids.size())), args.toArray());
    }

    private List<Map<String, Object>> outboxSummary(String tenantId, String storeId) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        String scope = storePredicate(storeId, args);
        return jdbcTemplate.queryForList("""
                SELECT status, COUNT(*) AS count, COALESCE(MAX(id), 0) AS lastId
                FROM web_change_outbox
                WHERE tenant_id = ?
                %s
                GROUP BY status
                ORDER BY status
                """.formatted(scope), args.toArray());
    }

    private Map<String, Object> oldestOutbox(String tenantId, String storeId) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        String scope = storePredicate(storeId, args);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, store_id AS storeId, entity_type AS entityType, entity_id AS entityId,
                       operation, status, attempts, error_count AS errorCount,
                       CAST(created_at AS CHAR) AS createdAt,
                       CAST(next_attempt_at AS CHAR) AS nextAttemptAt,
                       last_error AS lastError
                FROM web_change_outbox
                WHERE tenant_id = ?
                %s
                  AND status IN ('PENDING', 'DELIVERED', 'ERROR', 'DEAD')
                ORDER BY created_at ASC, id ASC
                LIMIT 1
                """.formatted(scope), args.toArray());
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private List<Map<String, Object>> recentOutboxErrors(String tenantId, String storeId) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        String scope = storePredicate(storeId, args);
        return jdbcTemplate.queryForList("""
                SELECT id, store_id AS storeId, entity_type AS entityType, entity_id AS entityId,
                       operation, status, error_count AS errorCount,
                       last_error AS lastError,
                       CAST(next_attempt_at AS CHAR) AS nextAttemptAt,
                       CAST(dead_letter_at AS CHAR) AS deadLetterAt
                FROM web_change_outbox
                WHERE tenant_id = ?
                %s
                  AND status IN ('ERROR', 'DEAD')
                ORDER BY COALESCE(dead_letter_at, next_attempt_at, delivered_at, created_at) DESC, id DESC
                LIMIT 10
                """.formatted(scope), args.toArray());
    }

    private List<Map<String, Object>> recentSyncRuns(String tenantId, String storeId) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        String scope = storePredicate(storeId, args);
        return jdbcTemplate.queryForList("""
                SELECT id, store_id AS storeId, source_id AS sourceId, device_id AS deviceId,
                       status, total_rows AS recordCount, message AS errorMessage,
                       CAST(received_at AS CHAR) AS receivedAt
                FROM sync_runs
                WHERE tenant_id = ?
                %s
                ORDER BY received_at DESC, id DESC
                LIMIT 10
                """.formatted(scope), args.toArray());
    }

    private long outboxCount(String tenantId, String storeId, String status) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        String scope = storePredicate(storeId, args);
        args.add(status);
        Long value = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM web_change_outbox
                WHERE tenant_id = ?
                %s
                  AND status = ?
                """.formatted(scope), Long.class, args.toArray());
        return value == null ? 0L : value;
    }

    private String storePredicate(String storeId, List<Object> args) {
        if (storeId == null || storeId.isBlank() || "all".equalsIgnoreCase(storeId)) {
            return "";
        }
        args.add(storeId.trim());
        return " AND store_id = ? ";
    }

    private Map<String, Object> statusPolicy() {
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("PENDING", "Aguardando entrega ao PDV.");
        policy.put("DELIVERED", "Enviado ao PDV e aguardando confirmação.");
        policy.put("ACKED", "Confirmado pelo PDV; não será enviado novamente.");
        policy.put("ERROR", "Falha informada pelo PDV; será reenviado no próximo horário permitido.");
        policy.put("DEAD", "Falha repetida; item pausado para não bloquear os próximos envios.");
        policy.put("maxErrorRetries", MAX_ERROR_RETRIES);
        return policy;
    }

    private Duration backoff(int errorCount) {
        return switch (Math.max(1, errorCount)) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            default -> Duration.ofMinutes(15);
        };
    }

    private void validateKnownSyncScope(String tenantId, String storeId, String sourceId, String deviceId) {
        if (!requireKnownDeviceScope) {
            return;
        }
        try {
            Integer knownStore = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM tenant_stores
                    WHERE tenant_id = ? AND id = ?
                    """, Integer.class, tenantId, storeId);
            if (knownStore == null || knownStore == 0) {
                return;
            }
            if ((sourceId == null || sourceId.isBlank()) && (deviceId == null || deviceId.isBlank())) {
                Integer knownDevices = jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM tenant_devices
                        WHERE tenant_id = ?
                          AND store_id = ?
                        """, Integer.class, tenantId, storeId);
                if (knownDevices != null && knownDevices > 0) {
                    throw new IllegalArgumentException("Não foi possível identificar qual PDV deve receber esta sincronização.");
                }
                return;
            }
            Integer matches = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM tenant_devices
                    WHERE tenant_id = ?
                      AND store_id = ?
                      AND (? IS NULL OR source_id = ?)
                      AND (? IS NULL OR id = ?)
                    """, Integer.class, tenantId, storeId, sourceId, sourceId, deviceId, deviceId);
            if (matches == null || matches == 0) {
                throw new IllegalArgumentException("Este PDV não está autorizado para sincronizar esta loja.");
            }
        } catch (DataAccessException ignored) {
            // Bancos legados podem ainda não ter metadados de devices no primeiro pull.
        }
    }

    private String knownStoreSourceId(String tenantId, String storeId) {
        try {
            List<String> rows = jdbcTemplate.query("""
                    SELECT source_id
                    FROM tenant_stores
                    WHERE tenant_id = ? AND id = ? AND source_id IS NOT NULL AND source_id <> ''
                    LIMIT 1
                    """, (rs, rowNum) -> rs.getString(1), tenantId, storeId);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (DataAccessException ignored) {
            return null;
        }
    }

    private String targetPredicate(String sourceId, String deviceId) {
        StringBuilder predicate = new StringBuilder();
        if (sourceId != null && !sourceId.isBlank()) {
            predicate.append(" AND (target_source_id IS NULL OR target_source_id = ?) ");
        }
        if (deviceId != null && !deviceId.isBlank()) {
            predicate.append(" AND (target_device_id IS NULL OR target_device_id = ?) ");
        }
        return predicate.toString();
    }

    private void addTargetArgs(List<Object> args, String sourceId, String deviceId) {
        if (sourceId != null && !sourceId.isBlank()) {
            args.add(sourceId);
        }
        if (deviceId != null && !deviceId.isBlank()) {
            args.add(deviceId);
        }
    }

    private int safeLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalArgumentException("Os dados de sincronização recebidos estão inválidos.", e);
        }
    }

    private Map<String, Object> fromJson(Object value) {
        if (value == null) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(String.valueOf(value), MAP_TYPE);
        } catch (Exception e) {
            return Map.of("raw", String.valueOf(value), "parseError", e.getMessage());
        }
    }

    private String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " é obrigatório");
        }
        return value.trim();
    }

    private String optional(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String cleanError(String error) {
        if (error == null || error.isBlank()) {
            return "Falha informada pelo PDV";
        }
        String clean = error.trim();
        return clean.length() <= 500 ? clean : clean.substring(0, 500);
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponivel no runtime Java.", e);
        }
    }

    private String placeholders(int count) {
        return String.join(", ", java.util.Collections.nCopies(count, "?"));
    }
}
