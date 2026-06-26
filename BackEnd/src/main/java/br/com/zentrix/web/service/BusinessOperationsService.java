package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.CancelSaleRequest;
import br.com.zentrix.web.dto.CashMovementRequest;
import br.com.zentrix.web.dto.CloseCashSessionRequest;
import br.com.zentrix.web.dto.EmployeeRequest;
import br.com.zentrix.web.dto.PermissionUpdateRequest;
import br.com.zentrix.web.dto.StockAdjustmentRequest;
import br.com.zentrix.web.service.PermissionService.Permission;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BusinessOperationsService {
    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final SettingsService settingsService;

    public BusinessOperationsService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            PermissionService permissionService,
            AuditService auditService,
            SettingsService settingsService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.settingsService = settingsService;
    }

    public Map<String, Object> saleDetail(String tenantId, String storeId, int id) {
        initializer.ensureReady();
        Map<String, Object> sale = single("""
                SELECT s.tenant_id, s.store_id, s.source_id, s.device_id, s.id, s.session_id, s.operator, s.discount,
                       s.surcharge, s.payment_method, s.amount_paid, s.status, s.date_time,
                       COALESCE(SUM((si.quantity * si.unit_price) - si.discount), 0) - s.discount + s.surcharge AS total
                FROM sales s
                LEFT JOIN sale_items si ON si.tenant_id = s.tenant_id AND si.store_id = s.store_id AND si.sale_id = s.id
                WHERE s.tenant_id = ? AND s.store_id = ? AND s.id = ?
                GROUP BY s.tenant_id, s.store_id, s.source_id, s.device_id, s.id, s.session_id, s.operator,
                         s.discount, s.surcharge, s.payment_method, s.amount_paid, s.status, s.date_time
                """, tenantId, normalizeStore(storeId), id);
        sale.put("items", rows("""
                SELECT id, product_code AS productCode, quantity, unit_price AS unitPrice, discount,
                       (quantity * unit_price) - discount AS total
                FROM sale_items
                WHERE tenant_id = ? AND store_id = ? AND sale_id = ?
                ORDER BY id
                """, tenantId, normalizeStore(storeId), id));
        sale.put("cancellation", rows("""
                SELECT id, reason, cancelled_by AS cancelledBy, cancelled_at AS cancelledAt
                FROM sale_cancellations
                WHERE tenant_id = ? AND store_id = ? AND sale_id = ?
                ORDER BY cancelled_at DESC, id DESC
                LIMIT 1
                """, tenantId, normalizeStore(storeId), id).stream().findFirst().orElse(null));
        return sale;
    }

    @Transactional
    public Map<String, Object> cancelSale(String tenantId, String storeId, int id, CancelSaleRequest request) {
        permissionService.require(Permission.CANCEL_SALE);
        String store = normalizeStore(storeId);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo do cancelamento.");
        }
        Map<String, Object> sale = saleDetail(tenantId, store, id);
        if ("CANCELLED".equalsIgnoreCase(String.valueOf(sale.get("status")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Venda já cancelada.");
        }
        jdbcTemplate.update("UPDATE sales SET status = 'CANCELLED' WHERE tenant_id = ? AND store_id = ? AND id = ?", tenantId, store, id);
        jdbcTemplate.update("""
                INSERT INTO sale_cancellations (tenant_id, store_id, device_id, source_id, id, sale_id, reason, cancelled_by, cancelled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE reason = VALUES(reason), cancelled_by = VALUES(cancelled_by), cancelled_at = VALUES(cancelled_at)
                """, tenantId, store, sale.get("device_id"), sale.get("source_id"), nextScopedId("sale_cancellations", tenantId, store), id, request.reason(), currentUser(), Timestamp.valueOf(LocalDateTime.now()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sale.get("items");
        for (Map<String, Object> item : items) {
            adjustStockInternal(tenantId, store, String.valueOf(item.get("productCode")), decimal(item.get("quantity")), "CANCELAMENTO", request.reason(), "SALE", String.valueOf(id), true);
        }
        auditService.recordCurrent("SALE_CANCELLED", "sales", String.valueOf(id), "Venda cancelada pelo AppGestão.", "ALERTA", request.reason());
        return saleDetail(tenantId, store, id);
    }

    public Map<String, Object> currentCash(String tenantId, String storeId) {
        List<Map<String, Object>> rows = rows("""
                SELECT id, cash_id AS cashId, operator, opening_balance AS openingBalance, opened_at AS openedAt, status
                FROM cash_sessions
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                  AND closed_at IS NULL
                  AND (is_open = TRUE OR UPPER(COALESCE(status, '')) IN ('OPEN', 'ABERTO'))
                ORDER BY COALESCE(opened_at, closed_at) DESC, id DESC
                LIMIT 1
                """, tenantId, normalizeStore(storeId), normalizeStore(storeId));
        return rows.isEmpty() ? Map.of("status", "CLOSED", "message", "Nenhum caixa aberto.") : rows.get(0);
    }

    public Map<String, Object> cashSession(String tenantId, String storeId, int id) {
        Map<String, Object> session = single("""
                SELECT id, cash_id AS cashId, operator, opening_balance AS openingBalance, closing_balance AS closingBalance,
                       expected_balance AS expectedBalance, difference, observation, opened_at AS openedAt, closed_at AS closedAt,
                       closed_by AS closedBy, close_reason AS closeReason, is_open AS open, status
                FROM cash_sessions
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, tenantId, normalizeStore(storeId), id);
        session.put("movements", rows("""
                SELECT id, type, value, observation, date_time AS dateTime
                FROM cash_movements
                WHERE tenant_id = ? AND store_id = ? AND session_id = ?
                ORDER BY date_time DESC, id DESC
                """, tenantId, normalizeStore(storeId), id));
        return session;
    }

    @Transactional
    public Map<String, Object> closeCash(String tenantId, String storeId, int id, CloseCashSessionRequest request) {
        permissionService.require(Permission.CLOSE_CASH);
        String store = normalizeStore(storeId);
        Map<String, Object> session = cashSession(tenantId, store, id);
        if (!Boolean.TRUE.equals(session.get("open"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Caixa já fechado.");
        }
        BigDecimal expected = expectedCash(tenantId, store, id, decimal(session.get("openingBalance")));
        BigDecimal difference = request.closingBalance().subtract(expected);
        if (difference.compareTo(BigDecimal.ZERO) != 0 && (request.reason() == null || request.reason().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fechamento com diferença exige justificativa.");
        }
        jdbcTemplate.update("""
                UPDATE cash_sessions
                SET is_open = FALSE, status = 'CLOSED', closing_balance = ?, expected_balance = ?, difference = ?,
                    closed_by = ?, close_reason = ?, closed_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, request.closingBalance(), expected, difference, currentUser(), request.reason(), tenantId, store, id);
        auditService.recordCurrent(difference.compareTo(BigDecimal.ZERO) == 0 ? "CASH_CLOSED" : "CASH_DIVERGENCE", "cash_sessions", String.valueOf(id), "Caixa fechado pelo AppGestão.", difference.compareTo(BigDecimal.ZERO) == 0 ? "INFO" : "ALERTA", request.reason());
        return cashSession(tenantId, store, id);
    }

    public Map<String, Object> withdrawal(String tenantId, String storeId, int id, CashMovementRequest request) {
        return cashMovement(tenantId, storeId, id, request, "SANGRIA", "CASH_WITHDRAWAL");
    }

    public Map<String, Object> supply(String tenantId, String storeId, int id, CashMovementRequest request) {
        return cashMovement(tenantId, storeId, id, request, "SUPRIMENTO", "CASH_SUPPLY");
    }

    @Transactional
    public Map<String, Object> adjustStock(String tenantId, String storeId, StockAdjustmentRequest request, String type) {
        permissionService.require(Permission.MANAGE_STOCK);
        boolean increase = "ENTRADA".equals(type);
        if (request == null || request.productCode() == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe produto, quantidade e motivo.");
        }
        Map<String, Object> row = adjustStockInternal(tenantId, normalizeStore(storeId), request.productCode(), request.quantity(), type, request.reason(), request.referenceType(), request.referenceId(), increase);
        auditService.recordCurrent("STOCK_ADJUSTED", "products", request.productCode(), "Movimentação de estoque registrada.", "ALERTA", request.reason());
        return row;
    }

    public List<Map<String, Object>> stockMovements(String tenantId, String storeId) {
        return rows("""
                SELECT id, product_code AS productCode, type, quantity, previous_stock AS previousStock, new_stock AS newStock,
                       origin, reference_type AS referenceType, reference_id AS referenceId, reason, user, created_at AS createdAt
                FROM stock_movements
                WHERE tenant_id = ? AND (? = 'all' OR store_id = ?)
                ORDER BY created_at DESC, id DESC
                LIMIT 100
                """, tenantId, normalizeStore(storeId), normalizeStore(storeId));
    }

    public Map<String, Object> employee(String tenantId, String username) {
        Map<String, Object> row = single("""
                SELECT tenant_id AS tenantId, store_id AS storeId, source_id AS sourceId, username, display_name AS displayName,
                       role, active, created_at AS createdAt, updated_at AS updatedAt, last_login_at AS lastLoginAt, permissions_json AS permissionsJson
                FROM users
                WHERE tenant_id = ? AND username = ?
                ORDER BY store_id
                LIMIT 1
                """, tenantId, username);
        row.remove("password");
        return row;
    }

    @Transactional
    public Map<String, Object> createEmployee(String tenantId, String storeId, EmployeeRequest request) {
        permissionService.require(Permission.MANAGE_USERS);
        String store = normalizeStore(storeId);
        String password = request.passwordHash();
        if ((password == null || password.isBlank()) && request.password() != null && !request.password().isBlank()) {
            password = BCrypt.hashpw(request.password(), BCrypt.gensalt());
        }
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a senha do usuário.");
        }
        jdbcTemplate.update("""
                INSERT INTO users (tenant_id, store_id, device_id, source_id, username, password, display_name, role, active, created_at, updated_at)
                VALUES (?, ?, NULL, 'WEB', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE display_name = VALUES(display_name), role = VALUES(role), active = VALUES(active), updated_at = CURRENT_TIMESTAMP
                """, tenantId, store, request.username(), password, request.displayName(), request.role(), request.active() == null || request.active());
        auditService.recordCurrent("USER_CREATED", "users", request.username(), "Usuário criado ou atualizado.", "ALERTA", null);
        return employee(tenantId, request.username());
    }

    @Transactional
    public Map<String, Object> updateEmployee(String tenantId, String username, EmployeeRequest request) {
        permissionService.require(Permission.MANAGE_USERS);
        jdbcTemplate.update("""
                UPDATE users SET display_name = ?, role = ?, active = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND username = ?
                """, request.displayName(), request.role(), request.active() == null || request.active(), tenantId, username);
        auditService.recordCurrent("USER_UPDATED", "users", username, "Usuário atualizado.", "ALERTA", null);
        return employee(tenantId, username);
    }

    public Map<String, Object> updateEmployeeStatus(String tenantId, String username, boolean active) {
        permissionService.require(Permission.MANAGE_USERS);
        jdbcTemplate.update("UPDATE users SET active = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND username = ?", active, tenantId, username);
        auditService.recordCurrent("USER_UPDATED", "users", username, active ? "Usuário ativado." : "Usuário inativado.", "ALERTA", null);
        return employee(tenantId, username);
    }

    public Map<String, Object> updatePermissions(String tenantId, String username, PermissionUpdateRequest request) {
        permissionService.require(Permission.MANAGE_PERMISSIONS);
        jdbcTemplate.update("UPDATE users SET permissions_json = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND username = ?", request.permissions().toString(), tenantId, username);
        auditService.recordCurrent("PERMISSION_UPDATED", "users", username, "Permissões atualizadas.", "CRITICO", null);
        return employee(tenantId, username);
    }

    public Map<String, Object> manualBackup(String tenantId, String storeId) {
        permissionService.require(Permission.MANAGE_SETTINGS);
        jdbcTemplate.update("""
                INSERT INTO backup_runs (tenant_id, store_id, source_id, status, total_rows, file_name, finished_at, message)
                VALUES (?, ?, 'WEB', 'SUCCESS', 0, ?, CURRENT_TIMESTAMP, 'Backup manual registrado.')
                """, tenantId, normalizeStore(storeId), "backup-" + tenantId + "-" + System.currentTimeMillis() + ".zip");
        auditService.recordCurrent("BACKUP_CREATED", "backup_runs", tenantId, "Backup manual registrado.", "INFO", null);
        return Map.of("status", "SUCCESS", "message", "Backup manual registrado.");
    }

    public Map<String, Object> restoreBackup(long id) {
        permissionService.require(Permission.RESTORE_BACKUP);
        auditService.recordCurrent("BACKUP_RESTORED", "backup_runs", String.valueOf(id), "Tentativa de restauração solicitada.", "CRITICO", "Restauração ainda não implementada");
        throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, "Restauração de backup ainda não implementada.");
    }

    private Map<String, Object> cashMovement(String tenantId, String storeId, int id, CashMovementRequest request, String type, String auditAction) {
        permissionService.require(Permission.CASH_MOVEMENT);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo da movimentação.");
        }
        String store = normalizeStore(storeId);
        cashSession(tenantId, store, id);
        jdbcTemplate.update("""
                INSERT INTO cash_movements (tenant_id, store_id, device_id, source_id, id, session_id, type, value, observation, date_time)
                VALUES (?, ?, NULL, 'WEB', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, tenantId, store, nextScopedId("cash_movements", tenantId, store), id, type, request.value(), request.observation() == null ? request.reason() : request.observation());
        auditService.recordCurrent(auditAction, "cash_sessions", String.valueOf(id), type + " registrada.", "ALERTA", request.reason());
        return cashSession(tenantId, store, id);
    }

    private Map<String, Object> adjustStockInternal(String tenantId, String store, String productCode, BigDecimal quantity, String type, String reason, String referenceType, String referenceId, boolean increase) {
        Map<String, Object> product = single("SELECT stock FROM products WHERE tenant_id = ? AND store_id = ? AND code = ?", tenantId, store, productCode);
        BigDecimal previous = decimal(product.get("stock"));
        BigDecimal delta = increase ? quantity.abs() : quantity.abs().negate();
        BigDecimal next = previous.add(delta);
        if (next.compareTo(BigDecimal.ZERO) < 0 && !settingsService.bool(tenantId, store, "permitir_estoque_negativo", false)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estoque negativo bloqueado para este produto.");
        }
        jdbcTemplate.update("UPDATE products SET stock = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND store_id = ? AND code = ?", next, tenantId, store, productCode);
        jdbcTemplate.update("""
                INSERT INTO stock_movements
                    (tenant_id, store_id, device_id, source_id, id, product_code, type, quantity, previous_stock, new_stock, origin, reference_type, reference_id, reason, user, created_at)
                VALUES (?, ?, NULL, 'WEB', ?, ?, ?, ?, ?, ?, 'APPGESTAO', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, tenantId, store, nextScopedId("stock_movements", tenantId, store), productCode, type, quantity.abs(), previous, next, referenceType, referenceId, reason, currentUser());
        return Map.of("productCode", productCode, "previousStock", previous, "newStock", next, "type", type);
    }

    private BigDecimal expectedCash(String tenantId, String store, int sessionId, BigDecimal openingBalance) {
        BigDecimal cashSales = decimal(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(amount_paid), 0)
                FROM sales
                WHERE tenant_id = ? AND store_id = ? AND session_id = ? AND status = 'PAID' AND payment_method = 'CASH'
                """, BigDecimal.class, tenantId, store, sessionId));
        BigDecimal supplies = decimal(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(value), 0) FROM cash_movements
                WHERE tenant_id = ? AND store_id = ? AND session_id = ? AND type IN ('SUPRIMENTO', 'SUPPLY')
                """, BigDecimal.class, tenantId, store, sessionId));
        BigDecimal withdrawals = decimal(jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(value), 0) FROM cash_movements
                WHERE tenant_id = ? AND store_id = ? AND session_id = ? AND type IN ('SANGRIA', 'WITHDRAWAL')
                """, BigDecimal.class, tenantId, store, sessionId));
        return openingBalance.add(cashSales).add(supplies).subtract(withdrawals);
    }

    private Map<String, Object> single(String sql, Object... args) {
        List<Map<String, Object>> rows = rows(sql, args);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registro não encontrado.");
        }
        return rows.get(0);
    }

    private List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbcTemplate.queryForList(sql, args);
    }

    private int nextScopedId(String table, String tenantId, String storeId) {
        Integer next = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM `" + table + "` WHERE tenant_id = ? AND store_id = ?", Integer.class, tenantId, storeId);
        return next == null ? 1 : next;
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(String.valueOf(value));
    }

    private String currentUser() {
        return AuthContext.current().map(AuthTokenService.SessionToken::username).orElse("sistema");
    }

    private String normalizeStore(String storeId) {
        return storeId == null || storeId.isBlank() || "all".equalsIgnoreCase(storeId) ? "WEB" : storeId;
    }
}
