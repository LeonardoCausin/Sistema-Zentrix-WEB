package br.com.zentrix.web.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LocalAdminService {
    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final PanelCacheService panelCacheService;
    private final AuditService auditService;

    public LocalAdminService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            PanelCacheService panelCacheService,
            AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.panelCacheService = panelCacheService;
        this.auditService = auditService;
    }

    public Map<String, Object> overview(String tenantId, String storeId) {
        initializer.ensureReady();
        String store = normalizeStore(storeId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("store", store);
        response.put("cashOpenCount", number("""
                SELECT COUNT(*)
                FROM cash_sessions
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                  AND (closed_at IS NULL OR is_open = TRUE)
                """, tenantId, store, store));
        response.put("cashStatusIssueCount", number("""
                SELECT COUNT(*)
                FROM cash_sessions
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                  AND closed_at IS NOT NULL
                  AND is_open = FALSE
                  AND UPPER(COALESCE(status, '')) IN ('OPEN', 'ABERTO')
                """, tenantId, store, store));
        response.put("syncFailureCount", number("""
                SELECT COUNT(*)
                FROM sync_runs sr
                WHERE sr.tenant_id = ? AND (? = 'all' OR sr.store_id = ?)
                  AND sr.status <> 'SUCCESS'
                  AND sr.received_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                  AND sr.received_at > COALESCE((
                        SELECT MAX(ok.received_at)
                        FROM sync_runs ok
                        WHERE ok.tenant_id = sr.tenant_id
                          AND ok.store_id = sr.store_id
                          AND ok.mode = sr.mode
                          AND ok.status = 'SUCCESS'
                          AND COALESCE(ok.source_id, '') = COALESCE(sr.source_id, '')
                  ), TIMESTAMP('1000-01-01 00:00:00'))
                """, tenantId, store, store));
        response.put("backupErrorCount", number("""
                SELECT COUNT(*)
                FROM backup_runs
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                  AND UPPER(COALESCE(status, '')) IN ('ERROR', 'ERRO', 'FAILED', 'FAILURE')
                """, tenantId, store, store));
        response.put("cashSessions", cashIssues(tenantId, store));
        response.put("syncFailures", syncFailures(tenantId, store));
        response.put("backupErrors", backupErrors(tenantId, store));
        return response;
    }

    public List<Map<String, Object>> cashIssues(String tenantId, String storeId) {
        initializer.ensureReady();
        String store = normalizeStore(storeId);
        return jdbcTemplate.queryForList("""
                SELECT id, store_id AS storeId, source_id AS sourceId, cash_id AS cashId, operator,
                       opening_balance AS openingBalance, closing_balance AS closingBalance,
                       expected_balance AS expectedBalance, difference, opened_at AS openedAt,
                       closed_at AS closedAt, is_open AS open, status
                FROM cash_sessions
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                  AND (
                    closed_at IS NULL
                    OR is_open = TRUE
                    OR (closed_at IS NOT NULL AND is_open = FALSE AND UPPER(COALESCE(status, '')) IN ('OPEN', 'ABERTO'))
                  )
                ORDER BY COALESCE(opened_at, closed_at) DESC, id DESC
                LIMIT 100
                """, tenantId, store, store);
    }

    public List<Map<String, Object>> syncFailures(String tenantId, String storeId) {
        initializer.ensureReady();
        String store = normalizeStore(storeId);
        return jdbcTemplate.queryForList("""
                SELECT id, store_id AS storeId, source_id AS sourceId, device_id AS deviceId,
                       status, total_rows AS totalRows, received_at AS receivedAt, message
                FROM sync_runs sr
                WHERE sr.tenant_id = ? AND (? = 'all' OR sr.store_id = ?)
                  AND sr.status <> 'SUCCESS'
                  AND sr.received_at >= DATE_SUB(NOW(), INTERVAL 7 DAY)
                  AND sr.received_at > COALESCE((
                        SELECT MAX(ok.received_at)
                        FROM sync_runs ok
                        WHERE ok.tenant_id = sr.tenant_id
                          AND ok.store_id = sr.store_id
                          AND ok.mode = sr.mode
                          AND ok.status = 'SUCCESS'
                          AND COALESCE(ok.source_id, '') = COALESCE(sr.source_id, '')
                  ), TIMESTAMP('1000-01-01 00:00:00'))
                ORDER BY sr.received_at DESC, sr.id DESC
                LIMIT 100
                """, tenantId, store, store);
    }

    public List<Map<String, Object>> backupErrors(String tenantId, String storeId) {
        initializer.ensureReady();
        String store = normalizeStore(storeId);
        return jdbcTemplate.queryForList("""
                SELECT id, store_id AS storeId, source_id AS sourceId, status, total_rows AS totalRows,
                       created_at AS createdAt, finished_at AS finishedAt, file_name AS fileName, message
                FROM backup_runs
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                  AND UPPER(COALESCE(status, '')) IN ('ERROR', 'ERRO', 'FAILED', 'FAILURE')
                ORDER BY created_at DESC, id DESC
                LIMIT 100
                """, tenantId, store, store);
    }

    @Transactional
    public Map<String, Object> normalizeCashStatuses(String tenantId, String storeId, Map<String, Object> request) {
        initializer.ensureReady();
        String reason = requiredReason(request);
        String store = normalizeStore(storeId);
        int updated = jdbcTemplate.update("""
                UPDATE cash_sessions
                SET status = 'CLOSED'
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                  AND closed_at IS NOT NULL
                  AND is_open = FALSE
                  AND UPPER(COALESCE(status, '')) IN ('OPEN', 'ABERTO')
                """, tenantId, store, store);
        auditService.recordCurrent("LOCAL_ADMIN_CASH_STATUS_NORMALIZED", "cash_sessions", store,
                "Status de caixas fechados normalizado pelo painel local. Registros: " + updated, "ALERTA", reason);
        panelCacheService.clear();
        return Map.of("updated", updated);
    }

    @Transactional
    public Map<String, Object> closeCash(String tenantId, String storeId, long id, Map<String, Object> request) {
        initializer.ensureReady();
        String reason = requiredReason(request);
        String store = normalizeWritableStore(storeId);
        Map<String, Object> session = singleCash(tenantId, store, id);
        BigDecimal opening = decimal(session.get("openingBalance"));
        BigDecimal closing = decimalOrDefault(request.get("closingBalance"), opening);
        BigDecimal expected = decimalOrDefault(request.get("expectedBalance"), closing);
        BigDecimal difference = closing.subtract(expected).setScale(2, RoundingMode.HALF_UP);
        Timestamp closedAt = timestampOrNow(request.get("closedAt"));
        int updated = jdbcTemplate.update("""
                UPDATE cash_sessions
                SET is_open = FALSE, status = 'CLOSED', closing_balance = ?, expected_balance = ?, difference = ?,
                    closed_by = ?, close_reason = ?, closed_at = ?
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, closing, expected, difference, currentUser(), reason, closedAt, tenantId, store, id);
        auditService.recordCurrent("LOCAL_ADMIN_CASH_CLOSED", "cash_sessions", String.valueOf(id),
                "Caixa fechado pelo painel local.", "ALERTA", reason);
        panelCacheService.clear();
        return Map.of("updated", updated, "id", id, "closingBalance", closing, "expectedBalance", expected, "difference", difference);
    }

    @Transactional
    public Map<String, Object> deleteCash(String tenantId, String storeId, long id, Map<String, Object> request) {
        initializer.ensureReady();
        String reason = requiredReason(request);
        String store = normalizeWritableStore(storeId);
        singleCash(tenantId, store, id);
        long sales = number("SELECT COUNT(*) FROM sales WHERE tenant_id = ? AND store_id = ? AND session_id = ?", tenantId, store, id);
        long movements = number("SELECT COUNT(*) FROM cash_movements WHERE tenant_id = ? AND store_id = ? AND session_id = ?", tenantId, store, id);
        if (sales > 0 || movements > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Este caixa possui vendas ou movimentos vinculados. Feche/corrija em vez de apagar.");
        }
        int deleted = jdbcTemplate.update("DELETE FROM cash_sessions WHERE tenant_id = ? AND store_id = ? AND id = ?", tenantId, store, id);
        auditService.recordCurrent("LOCAL_ADMIN_CASH_DELETED", "cash_sessions", String.valueOf(id),
                "Caixa sem vinculos removido pelo painel local.", "CRITICO", reason);
        panelCacheService.clear();
        return Map.of("deleted", deleted, "id", id);
    }

    @Transactional
    public Map<String, Object> clearSyncFailures(String tenantId, String storeId, Map<String, Object> request) {
        initializer.ensureReady();
        String reason = requiredReason(request);
        String store = normalizeStore(storeId);
        int days = days(request.get("days"));
        int deleted = jdbcTemplate.update("""
                DELETE FROM sync_runs
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                  AND status <> 'SUCCESS'
                  AND received_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                """, tenantId, store, store, days);
        auditService.recordCurrent("LOCAL_ADMIN_SYNC_FAILURES_CLEARED", "sync_runs", store,
                "Falhas de sincronizacao removidas pelo painel local. Registros: " + deleted, "CRITICO", reason);
        panelCacheService.clear();
        return Map.of("deleted", deleted, "days", days);
    }

    @Transactional
    public Map<String, Object> clearBackupErrors(String tenantId, String storeId, Map<String, Object> request) {
        initializer.ensureReady();
        String reason = requiredReason(request);
        String store = normalizeStore(storeId);
        int deleted = jdbcTemplate.update("""
                DELETE FROM backup_runs
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                  AND UPPER(COALESCE(status, '')) IN ('ERROR', 'ERRO', 'FAILED', 'FAILURE')
                """, tenantId, store, store);
        auditService.recordCurrent("LOCAL_ADMIN_BACKUP_ERRORS_CLEARED", "backup_runs", store,
                "Backups com erro removidos pelo painel local. Registros: " + deleted, "CRITICO", reason);
        panelCacheService.clear();
        return Map.of("deleted", deleted);
    }

    public Map<String, Object> clearCache(Map<String, Object> request) {
        String reason = requiredReason(request);
        panelCacheService.clear();
        auditService.recordCurrent("LOCAL_ADMIN_CACHE_CLEARED", "panel_cache", "all",
                "Cache do painel limpo pelo painel local.", "ALERTA", reason);
        return Map.of("cleared", true);
    }

    private Map<String, Object> singleCash(String tenantId, String store, long id) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, store_id AS storeId, cash_id AS cashId, operator, opening_balance AS openingBalance
                FROM cash_sessions
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                LIMIT 1
                """, tenantId, store, id);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Caixa nao encontrado.");
        }
        return rows.get(0);
    }

    private String requiredReason(Map<String, Object> request) {
        String reason = text(request == null ? null : request.get("reason"));
        if (reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo da manutencao.");
        }
        return reason;
    }

    private long number(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String normalizeStore(String storeId) {
        String value = text(storeId);
        return value.isBlank() ? "all" : value;
    }

    private String normalizeWritableStore(String storeId) {
        String value = text(storeId);
        if (value.isBlank() || "all".equalsIgnoreCase(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Escolha uma loja especifica para esta acao.");
        }
        return value;
    }

    private BigDecimal decimal(Object value) {
        return decimalOrDefault(value, BigDecimal.ZERO);
    }

    private BigDecimal decimalOrDefault(Object value, BigDecimal fallback) {
        if (value == null || text(value).isBlank()) {
            return money(fallback);
        }
        try {
            return money(new BigDecimal(text(value).replace(",", ".")));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor monetario invalido.");
        }
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private Timestamp timestampOrNow(Object value) {
        String text = text(value);
        if (text.isBlank()) {
            return Timestamp.valueOf(LocalDateTime.now());
        }
        try {
            return Timestamp.valueOf(LocalDateTime.parse(text.replace(' ', 'T')));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data de fechamento invalida.");
        }
    }

    private int days(Object value) {
        if (value == null || text(value).isBlank()) {
            return 7;
        }
        try {
            return Math.max(1, Math.min(Integer.parseInt(text(value)), 365));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Dias invalido.");
        }
    }

    private String currentUser() {
        return AuthContext.current().map(AuthTokenService.SessionToken::username).orElse("local-admin");
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
