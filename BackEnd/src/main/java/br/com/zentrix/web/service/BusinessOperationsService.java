package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.CancelSaleRequest;
import br.com.zentrix.web.dto.CashMovementRequest;
import br.com.zentrix.web.dto.CloseCashSessionRequest;
import br.com.zentrix.web.dto.ClientRequest;
import br.com.zentrix.web.dto.EmployeeRequest;
import br.com.zentrix.web.dto.FinancialEntryRequest;
import br.com.zentrix.web.dto.FinancialEntryStatusRequest;
import br.com.zentrix.web.dto.PermissionUpdateRequest;
import br.com.zentrix.web.dto.ProductPriceRequest;
import br.com.zentrix.web.dto.ProductRequest;
import br.com.zentrix.web.dto.StockAdjustmentRequest;
import br.com.zentrix.web.service.PermissionService.Permission;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.mindrot.jbcrypt.BCrypt;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BusinessOperationsService {
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final PermissionService permissionService;
    private final AuditService auditService;
    private final SettingsService settingsService;
    private final AuthTokenService authTokenService;
    private final WebChangeOutboxService webChangeOutboxService;
    private final PanelCacheService panelCacheService;

    public BusinessOperationsService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            PermissionService permissionService,
            AuditService auditService,
            SettingsService settingsService,
            AuthTokenService authTokenService,
            WebChangeOutboxService webChangeOutboxService,
            PanelCacheService panelCacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.settingsService = settingsService;
        this.authTokenService = authTokenService;
        this.webChangeOutboxService = webChangeOutboxService;
        this.panelCacheService = panelCacheService;
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
        String store = normalizeWritableStore(tenantId, storeId);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo do cancelamento.");
        }
        Map<String, Object> sale = saleDetail(tenantId, store, id);
        if ("CANCELLED".equalsIgnoreCase(String.valueOf(sale.get("status")))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Venda já cancelada.");
        }
        jdbcTemplate.update("UPDATE sales SET status = 'CANCELLED' WHERE tenant_id = ? AND store_id = ? AND id = ?", tenantId, store, id);
        int cancellationId = nextScopedId("sale_cancellations", tenantId, store);
        jdbcTemplate.update("""
                INSERT INTO sale_cancellations (tenant_id, store_id, device_id, source_id, id, sale_id, reason, cancelled_by, cancelled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE reason = VALUES(reason), cancelled_by = VALUES(cancelled_by), cancelled_at = VALUES(cancelled_at)
                """, tenantId, store, sale.get("device_id"), sale.get("source_id"), cancellationId, id, request.reason(), currentUser(), Timestamp.valueOf(LocalDateTime.now()));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) sale.get("items");
        for (Map<String, Object> item : items) {
            adjustStockInternal(tenantId, store, String.valueOf(item.get("productCode")), decimal(item.get("quantity")), "CANCELAMENTO", request.reason(), "SALE", String.valueOf(id), true);
        }
        auditService.recordCurrent("SALE_CANCELLED", "sales", String.valueOf(id), "Venda cancelada pelo AppGestão.", "ALERTA", request.reason());
        enqueueSaleChange(tenantId, store, id, "SALE_CANCELLED");
        enqueueSaleCancellationChange(tenantId, store, cancellationId, "SALE_CANCELLATION_CREATED");
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
        String store = normalizeWritableStore(tenantId, storeId);
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
        enqueueCashSessionChange(tenantId, store, id, "CASH_SESSION_CLOSED");
        return cashSession(tenantId, store, id);
    }

    @Transactional
    public Map<String, Object> withdrawal(String tenantId, String storeId, int id, CashMovementRequest request) {
        return cashMovement(tenantId, storeId, id, request, "SANGRIA", "CASH_WITHDRAWAL");
    }

    @Transactional
    public Map<String, Object> supply(String tenantId, String storeId, int id, CashMovementRequest request) {
        return cashMovement(tenantId, storeId, id, request, "SUPRIMENTO", "CASH_SUPPLY");
    }

    @Transactional
    public Map<String, Object> adjustStock(String tenantId, String storeId, StockAdjustmentRequest request, String type) {
        permissionService.require(Permission.STOCK_MOVE);
        boolean increase = "ENTRADA".equals(type);
        if (request == null || request.productCode() == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe produto, quantidade e motivo.");
        }
        Map<String, Object> row = adjustStockInternal(tenantId, normalizeWritableStore(tenantId, storeId), request.productCode(), request.quantity(), type, request.reason(), request.referenceType(), request.referenceId(), increase);
        auditService.recordCurrent("STOCK_ADJUSTED", "products", request.productCode(), "Movimentação de estoque registrada.", "ALERTA", request.reason());
        return row;
    }

    public List<Map<String, Object>> stockMovements(String tenantId, String storeId) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("tenant_id = ?");
        args.add(tenantId);
        String store = normalizeListStore(storeId);
        if (store != null) {
            where.append(" AND store_id = ?");
            args.add(store);
        }
        return rows("""
                SELECT id, product_code AS productCode, type, quantity, previous_stock AS previousStock, new_stock AS newStock,
                       origin, reference_type AS referenceType, reference_id AS referenceId, reason, user, created_at AS createdAt
                FROM stock_movements
                WHERE %s
                ORDER BY created_at DESC, id DESC
                LIMIT 100
                """.formatted(where), args.toArray());
    }

    public List<Map<String, Object>> adminProducts(String tenantId, String storeId, String search, String category, String status, int limit, int offset) {
        permissionService.require(Permission.VIEW_PANEL);
        initializer.ensureReady();
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("tenant_id = ? AND deleted_at IS NULL");
        args.add(tenantId);
        String store = normalizeListStore(storeId);
        if (store != null) {
            where.append(" AND store_id = ?");
            args.add(store);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (description LIKE ? OR code LIKE ? OR barcode LIKE ? OR category LIKE ?)");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (category != null && !category.isBlank()) {
            where.append(" AND category = ?");
            args.add(category.trim());
        }
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            where.append(" AND active = ?");
            args.add(isActiveStatus(status));
        }
        args.add(safeLimit(limit, 100, 500));
        args.add(safeOffset(offset));
        return jdbcTemplate.query("""
                SELECT tenant_id, store_id, source_id, code, description, unit, price, cost_price, stock,
                       min_stock, ideal_stock, category, barcode, active, created_at, updated_at
                FROM products
                WHERE %s
                ORDER BY description
                LIMIT ? OFFSET ?
                """.formatted(where), (rs, rowNum) -> productRow(
                rs.getString("tenant_id"),
                rs.getString("store_id"),
                rs.getString("source_id"),
                rs.getString("code"),
                rs.getString("description"),
                rs.getString("unit"),
                rs.getBigDecimal("price"),
                rs.getBigDecimal("cost_price"),
                rs.getBigDecimal("stock"),
                rs.getBigDecimal("min_stock"),
                rs.getBigDecimal("ideal_stock"),
                rs.getString("category"),
                rs.getString("barcode"),
                rs.getBoolean("active"),
                timestampText(rs.getTimestamp("created_at")),
                timestampText(rs.getTimestamp("updated_at"))
        ), args.toArray());
    }

    public Map<String, Object> adminProduct(String tenantId, String storeId, String code) {
        permissionService.require(Permission.VIEW_PANEL);
        Map<String, Object> row = single("""
                SELECT tenant_id, store_id, source_id, code, description, unit, price, cost_price, stock,
                       min_stock, ideal_stock, category, barcode, active, created_at, updated_at
                FROM products
                WHERE tenant_id = ? AND store_id = ? AND code = ? AND deleted_at IS NULL
                """, tenantId, normalizeStore(storeId), code);
        return productRow(row);
    }

    @Transactional
    public Map<String, Object> createProduct(String tenantId, String storeId, ProductRequest request) {
        permissionService.require(Permission.PRODUCTS_CREATE);
        String store = normalizeWritableStore(tenantId, storeId);
        validateProductRequest(request, true);
        String code = request.code().trim();
        if (!rows("SELECT code FROM products WHERE tenant_id = ? AND store_id = ? AND code = ? AND deleted_at IS NULL", tenantId, store, code).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Produto ja cadastrado para esta loja.");
        }
        jdbcTemplate.update("""
                INSERT INTO products
                    (tenant_id, store_id, device_id, source_id, code, description, unit, price, cost_price, stock,
                     supplier_id, min_stock, category, barcode, created_at, ideal_stock, active, updated_at, deleted_at)
                VALUES (?, ?, NULL, 'WEB', ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, CURRENT_TIMESTAMP, NULL)
                """,
                tenantId,
                store,
                code,
                request.description().trim(),
                productUnit(request.unit()),
                money(request.price()),
                money(request.costPrice()),
                quantity(request.stock()),
                quantity(request.minStock()),
                clean(request.category()),
                clean(request.barcode()),
                quantity(request.idealStock()),
                request.active() == null || request.active()
        );
        auditService.recordCurrent("PRODUCT_CREATED", "products", code, "Produto criado pelo AppGestao.", "ALERTA", null);
        enqueueProductChange(tenantId, store, code, "PRODUCT_UPSERT");
        return adminProduct(tenantId, store, code);
    }

    @Transactional
    public Map<String, Object> updateProduct(String tenantId, String storeId, String code, ProductRequest request) {
        permissionService.require(Permission.PRODUCTS_EDIT);
        String store = normalizeWritableStore(tenantId, storeId);
        validateProductRequest(request, false);
        if (request.code() != null && !request.code().isBlank() && !code.equals(request.code().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O codigo do produto nao pode ser alterado por esta rota.");
        }
        Map<String, Object> before = adminProduct(tenantId, store, code);
        BigDecimal previousPrice = decimal(before.get("price"));
        BigDecimal nextPrice = money(request.price());
        jdbcTemplate.update("""
                UPDATE products
                SET description = ?, unit = ?, price = ?, cost_price = ?, min_stock = ?, ideal_stock = ?,
                    category = ?, barcode = ?, active = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND store_id = ? AND code = ? AND deleted_at IS NULL
                """,
                request.description().trim(),
                productUnit(request.unit()),
                nextPrice,
                money(request.costPrice()),
                quantity(request.minStock()),
                quantity(request.idealStock()),
                clean(request.category()),
                clean(request.barcode()),
                request.active() == null || request.active(),
                tenantId,
                store,
                code
        );
        auditService.recordCurrent("PRODUCT_UPDATED", "products", code, "Produto atualizado pelo AppGestao.", "ALERTA", null);
        if (previousPrice.compareTo(nextPrice) != 0) {
            auditService.recordCurrent("PRODUCT_PRICE_CHANGED", "products", code, "Preco de venda alterado.", "CRITICO", null);
        }
        enqueueProductChange(tenantId, store, code, "PRODUCT_UPSERT");
        return adminProduct(tenantId, store, code);
    }

    @Transactional
    public Map<String, Object> updateProductStatus(String tenantId, String storeId, String code, boolean active, String reason) {
        permissionService.require(Permission.PRODUCTS_DISABLE);
        String store = normalizeWritableStore(tenantId, storeId);
        adminProduct(tenantId, store, code);
        jdbcTemplate.update("""
                UPDATE products
                SET active = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND store_id = ? AND code = ? AND deleted_at IS NULL
                """, active, tenantId, store, code);
        auditService.recordCurrent(active ? "PRODUCT_REACTIVATED" : "PRODUCT_DEACTIVATED", "products", code, active ? "Produto reativado." : "Produto desativado.", "ALERTA", reason);
        enqueueProductChange(tenantId, store, code, active ? "PRODUCT_REACTIVATED" : "PRODUCT_DEACTIVATED");
        return adminProduct(tenantId, store, code);
    }

    @Transactional
    public Map<String, Object> updateProductPrice(String tenantId, String storeId, String code, ProductPriceRequest request) {
        permissionService.require(Permission.PRODUCTS_EDIT);
        if (request == null || request.price() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o preco de venda.");
        }
        if (request.price().compareTo(BigDecimal.ZERO) < 0 || (request.costPrice() != null && request.costPrice().compareTo(BigDecimal.ZERO) < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Precos devem ser maiores ou iguais a zero.");
        }
        String store = normalizeWritableStore(tenantId, storeId);
        Map<String, Object> before = adminProduct(tenantId, store, code);
        BigDecimal price = money(request.price());
        BigDecimal cost = request.costPrice() == null ? decimal(before.get("costPrice")) : money(request.costPrice());
        jdbcTemplate.update("""
                UPDATE products
                SET price = ?, cost_price = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND store_id = ? AND code = ? AND deleted_at IS NULL
                """, price, cost, tenantId, store, code);
        auditService.recordCurrent("PRODUCT_PRICE_CHANGED", "products", code, "Preco de produto alterado.", "CRITICO", request.reason());
        enqueueProductChange(tenantId, store, code, "PRODUCT_PRICE_CHANGED");
        return adminProduct(tenantId, store, code);
    }

    public List<Map<String, Object>> adminClients(String tenantId, String storeId, String search, String status, int limit, int offset) {
        permissionService.require(Permission.VIEW_PANEL);
        initializer.ensureReady();
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("tenant_id = ? AND deleted_at IS NULL");
        args.add(tenantId);
        String store = normalizeListStore(storeId);
        if (store != null) {
            where.append(" AND store_id = ?");
            args.add(store);
        }
        if (search != null && !search.isBlank()) {
            where.append(" AND (name LIKE ? OR cpf_cnpj LIKE ? OR phone LIKE ? OR email LIKE ?)");
            String like = "%" + search.trim() + "%";
            args.add(like);
            args.add(like);
            args.add(like);
            args.add(like);
        }
        if (status != null && !status.isBlank() && !"all".equalsIgnoreCase(status)) {
            where.append(" AND active = ?");
            args.add(isActiveStatus(status));
        }
        args.add(safeLimit(limit, 100, 500));
        args.add(safeOffset(offset));
        return jdbcTemplate.query("""
                SELECT tenant_id, store_id, source_id, id, name, cpf_cnpj, phone, email, address, created_at,
                       birth_date, active, notes, loyalty_points, updated_at
                FROM clients
                WHERE %s
                ORDER BY name
                LIMIT ? OFFSET ?
                """.formatted(where), (rs, rowNum) -> clientRow(
                rs.getString("tenant_id"),
                rs.getString("store_id"),
                rs.getString("source_id"),
                rs.getInt("id"),
                rs.getString("name"),
                rs.getString("cpf_cnpj"),
                rs.getString("phone"),
                rs.getString("email"),
                rs.getString("address"),
                rs.getDate("birth_date") == null ? null : rs.getDate("birth_date").toString(),
                rs.getBoolean("active"),
                rs.getString("notes"),
                rs.getInt("loyalty_points"),
                timestampText(rs.getTimestamp("created_at")),
                timestampText(rs.getTimestamp("updated_at"))
        ), args.toArray());
    }

    public Map<String, Object> adminClient(String tenantId, String storeId, int id) {
        permissionService.require(Permission.VIEW_PANEL);
        Map<String, Object> row = single("""
                SELECT tenant_id, store_id, source_id, id, name, cpf_cnpj, phone, email, address, created_at,
                       birth_date, active, notes, loyalty_points, updated_at
                FROM clients
                WHERE tenant_id = ? AND store_id = ? AND id = ? AND deleted_at IS NULL
                """, tenantId, normalizeStore(storeId), id);
        return clientRow(row);
    }

    @Transactional
    public Map<String, Object> createClient(String tenantId, String storeId, ClientRequest request) {
        permissionService.require(Permission.CLIENTS_CREATE);
        String store = normalizeWritableStore(tenantId, storeId);
        validateClientRequest(request);
        int id = request.id() == null ? nextScopedId("clients", tenantId, store) : request.id();
        if (!rows("SELECT id FROM clients WHERE tenant_id = ? AND store_id = ? AND id = ? AND deleted_at IS NULL", tenantId, store, id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Cliente ja cadastrado com este codigo.");
        }
        jdbcTemplate.update("""
                INSERT INTO clients
                    (tenant_id, store_id, device_id, source_id, id, name, cpf_cnpj, phone, email, address,
                     created_at, birth_date, active, notes, loyalty_points, updated_at, deleted_at)
                VALUES (?, ?, NULL, 'WEB', ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, ?, ?, ?, ?, CURRENT_TIMESTAMP, NULL)
                """,
                tenantId,
                store,
                id,
                request.name().trim(),
                clean(request.cpfCnpj()),
                clean(request.phone()),
                clean(request.email()),
                clean(request.address()),
                request.birthDate(),
                request.active() == null || request.active(),
                clean(request.notes()),
                request.loyaltyPoints() == null ? 0 : Math.max(0, request.loyaltyPoints())
        );
        auditService.recordCurrent("CLIENT_CREATED", "clients", String.valueOf(id), "Cliente criado pelo AppGestao.", "INFO", null);
        enqueueClientChange(tenantId, store, id, "CLIENT_UPSERT");
        return adminClient(tenantId, store, id);
    }

    @Transactional
    public Map<String, Object> updateClient(String tenantId, String storeId, int id, ClientRequest request) {
        permissionService.require(Permission.CLIENTS_EDIT);
        String store = normalizeWritableStore(tenantId, storeId);
        validateClientRequest(request);
        adminClient(tenantId, store, id);
        jdbcTemplate.update("""
                UPDATE clients
                SET name = ?, cpf_cnpj = ?, phone = ?, email = ?, address = ?, birth_date = ?,
                    active = ?, notes = ?, loyalty_points = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND store_id = ? AND id = ? AND deleted_at IS NULL
                """,
                request.name().trim(),
                clean(request.cpfCnpj()),
                clean(request.phone()),
                clean(request.email()),
                clean(request.address()),
                request.birthDate(),
                request.active() == null || request.active(),
                clean(request.notes()),
                request.loyaltyPoints() == null ? 0 : Math.max(0, request.loyaltyPoints()),
                tenantId,
                store,
                id
        );
        auditService.recordCurrent("CLIENT_UPDATED", "clients", String.valueOf(id), "Cliente atualizado pelo AppGestao.", "INFO", null);
        enqueueClientChange(tenantId, store, id, "CLIENT_UPSERT");
        return adminClient(tenantId, store, id);
    }

    @Transactional
    public Map<String, Object> updateClientStatus(String tenantId, String storeId, int id, boolean active, String reason) {
        permissionService.require(Permission.CLIENTS_EDIT);
        String store = normalizeWritableStore(tenantId, storeId);
        adminClient(tenantId, store, id);
        jdbcTemplate.update("""
                UPDATE clients
                SET active = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND store_id = ? AND id = ? AND deleted_at IS NULL
                """, active, tenantId, store, id);
        auditService.recordCurrent(active ? "CLIENT_REACTIVATED" : "CLIENT_DEACTIVATED", "clients", String.valueOf(id), active ? "Cliente reativado." : "Cliente desativado.", "INFO", reason);
        enqueueClientChange(tenantId, store, id, active ? "CLIENT_REACTIVATED" : "CLIENT_DEACTIVATED");
        return adminClient(tenantId, store, id);
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
        row.put("permissions", permissionsFrom(row.get("permissionsJson")));
        return row;
    }

    @Transactional
    public Map<String, Object> createEmployee(String tenantId, String storeId, EmployeeRequest request) {
        permissionService.require(Permission.USERS_CREATE);
        String store = normalizeWritableStore(tenantId, storeId);
        String username = request.username().trim();
        String password = resolvedPassword(request, true);
        if ((password == null || password.isBlank()) && request.password() != null && !request.password().isBlank()) {
            password = BCrypt.hashpw(request.password(), BCrypt.gensalt());
        }
        if (password == null || password.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a senha do usuário.");
        }
        jdbcTemplate.update("""
                INSERT INTO users (tenant_id, store_id, device_id, source_id, username, password, display_name, role, active, created_at, updated_at)
                VALUES (?, ?, NULL, 'WEB', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON DUPLICATE KEY UPDATE password = VALUES(password), display_name = VALUES(display_name), role = VALUES(role), active = VALUES(active), updated_at = CURRENT_TIMESTAMP
                """, tenantId, store, username, password, request.displayName().trim(), request.role().trim(), request.active() == null || request.active());
        authTokenService.revokeUser(username);
        auditService.recordCurrent("USER_CREATED", "users", request.username(), "Usuário criado ou atualizado.", "ALERTA", null);
        enqueueEmployeeChange(tenantId, store, username, "USER_UPSERT");
        return employee(tenantId, username);
    }

    @Transactional
    public Map<String, Object> updateEmployee(String tenantId, String username, EmployeeRequest request) {
        permissionService.require(Permission.USERS_EDIT);
        if (request.username() != null && !request.username().isBlank() && !username.equalsIgnoreCase(request.username().trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "O login do usuario nao pode ser alterado por esta rota.");
        }
        Map<String, Object> before = employee(tenantId, username);
        boolean disabling = request.active() != null && !request.active();
        boolean removingAdminRole = isAdminRole(String.valueOf(before.get("role"))) && !isAdminRole(request.role());
        if (disabling || removingAdminRole) {
            guardLastActiveAdmin(tenantId, username);
        }
        String store = String.valueOf(before.get("storeId"));
        String password = resolvedPassword(request, false);
        if (password == null) {
            jdbcTemplate.update("""
                    UPDATE users SET display_name = ?, role = ?, active = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND store_id = ? AND username = ?
                    """, request.displayName().trim(), request.role().trim(), request.active() == null || request.active(), tenantId, store, username);
        } else {
            jdbcTemplate.update("""
                    UPDATE users SET password = ?, display_name = ?, role = ?, active = ?, updated_at = CURRENT_TIMESTAMP
                    WHERE tenant_id = ? AND store_id = ? AND username = ?
                    """, password, request.displayName().trim(), request.role().trim(), request.active() == null || request.active(), tenantId, store, username);
        }
        authTokenService.revokeUser(username);
        auditService.recordCurrent("USER_UPDATED", "users", username, "Usuário atualizado.", "ALERTA", null);
        enqueueEmployeeChange(tenantId, store, username, "USER_UPSERT");
        return employee(tenantId, username);
    }

    @Transactional
    public Map<String, Object> updateEmployeeStatus(String tenantId, String username, boolean active) {
        permissionService.require(Permission.USERS_EDIT);
        if (!active) {
            guardLastActiveAdmin(tenantId, username);
        }
        Map<String, Object> before = employee(tenantId, username);
        String store = String.valueOf(before.get("storeId"));
        jdbcTemplate.update("UPDATE users SET active = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND store_id = ? AND username = ?", active, tenantId, store, username);
        if (!active) {
            authTokenService.revokeUser(username);
        }
        auditService.recordCurrent("USER_UPDATED", "users", username, active ? "Usuário ativado." : "Usuário inativado.", "ALERTA", null);
        enqueueEmployeeChange(tenantId, store, username, active ? "USER_REACTIVATED" : "USER_DEACTIVATED");
        return employee(tenantId, username);
    }

    @Transactional
    public Map<String, Object> updatePermissions(String tenantId, String username, PermissionUpdateRequest request) {
        permissionService.require(Permission.USERS_PERMISSIONS);
        Map<String, Object> before = employee(tenantId, username);
        String store = String.valueOf(before.get("storeId"));
        jdbcTemplate.update("UPDATE users SET permissions_json = ?, updated_at = CURRENT_TIMESTAMP WHERE tenant_id = ? AND store_id = ? AND username = ?", permissionsJson(request.permissions()), tenantId, store, username);
        authTokenService.revokeUser(username);
        auditService.recordCurrent("PERMISSION_UPDATED", "users", username, "Permissões atualizadas.", "CRITICO", null);
        enqueueEmployeeChange(tenantId, store, username, "USER_PERMISSIONS_CHANGED");
        return employee(tenantId, username);
    }

    public List<Map<String, Object>> financialEntries(String tenantId, String storeId, String period, String type, String status, int limit, int offset) {
        permissionService.require(Permission.MANAGE_FINANCE);
        initializer.ensureReady();
        String store = normalizeListStore(storeId);
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder("tenant_id = ?");
        args.add(tenantId);
        if (store != null) {
            where.append(" AND store_id = ?");
            args.add(store);
        }
        where.append(financialPeriodClause(period, tenantId, store, args));
        String normalizedType = normalizeFinancialTypeOrNull(type);
        if (normalizedType != null) {
            where.append(" AND type = ?");
            args.add(normalizedType);
        }
        String normalizedStatus = normalizeFinancialStatusOrNull(status);
        if (normalizedStatus != null) {
            where.append(" AND status = ?");
            args.add(normalizedStatus);
        }
        args.add(safeLimit(limit, 100, 500));
        args.add(safeOffset(offset));
        return jdbcTemplate.query("""
                SELECT tenant_id, store_id, source_id, id, type, category, description, amount, entry_date,
                       status, created_by, created_at, updated_at, origin, notes
                FROM financial_entries
                WHERE %s
                ORDER BY entry_date DESC, id DESC
                LIMIT ? OFFSET ?
                """.formatted(where), (rs, rowNum) -> financialEntryRow(
                rs.getString("tenant_id"),
                rs.getString("store_id"),
                rs.getString("source_id"),
                rs.getInt("id"),
                rs.getString("type"),
                rs.getString("category"),
                rs.getString("description"),
                rs.getBigDecimal("amount"),
                rs.getDate("entry_date") == null ? null : rs.getDate("entry_date").toLocalDate(),
                rs.getString("status"),
                rs.getString("created_by"),
                timestampText(rs.getTimestamp("created_at")),
                timestampText(rs.getTimestamp("updated_at")),
                rs.getString("origin"),
                rs.getString("notes")
        ), args.toArray());
    }

    public Map<String, Object> financialEntry(String tenantId, String storeId, int id) {
        permissionService.require(Permission.MANAGE_FINANCE);
        String store = normalizeStore(storeId);
        Map<String, Object> row = single("""
                SELECT tenant_id, store_id, source_id, id, type, category, description, amount, entry_date,
                       status, created_by, created_at, updated_at, origin, notes
                FROM financial_entries
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, tenantId, store, id);
        return financialEntryRow(row);
    }

    @Transactional
    public Map<String, Object> createFinancialEntry(String tenantId, String storeId, FinancialEntryRequest request) {
        permissionService.require(Permission.MANAGE_FINANCE);
        validateFinancialEntry(request);
        String store = normalizeWritableStore(tenantId, storeId);
        int id = nextScopedId("financial_entries", tenantId, store);
        jdbcTemplate.update("""
                INSERT INTO financial_entries
                    (tenant_id, store_id, device_id, source_id, id, type, category, description, amount,
                     entry_date, status, created_by, created_at, updated_at, origin, notes)
                VALUES (?, ?, NULL, 'WEB', ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP, NULL, 'APPGESTAO', ?)
                """,
                tenantId,
                store,
                id,
                normalizeFinancialType(request.type()),
                cleanRequired(request.category()),
                cleanRequired(request.description()),
                money(request.amount()),
                request.entryDate(),
                normalizeFinancialStatus(request.status()),
                currentUser(),
                clean(request.notes())
        );
        auditService.recordCurrent("FINANCIAL_ENTRY_CREATED", "financial_entries", String.valueOf(id), "Lançamento financeiro criado pelo AppGestão.", "ALERTA", null);
        enqueueFinancialEntryChange(tenantId, store, id, "FINANCIAL_ENTRY_CREATED");
        return financialEntry(tenantId, store, id);
    }

    @Transactional
    public Map<String, Object> updateFinancialEntry(String tenantId, String storeId, int id, FinancialEntryRequest request) {
        permissionService.require(Permission.MANAGE_FINANCE);
        validateFinancialEntry(request);
        String store = normalizeWritableStore(tenantId, storeId);
        financialEntry(tenantId, store, id);
        jdbcTemplate.update("""
                UPDATE financial_entries
                SET type = ?, category = ?, description = ?, amount = ?, entry_date = ?,
                    status = ?, notes = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """,
                normalizeFinancialType(request.type()),
                cleanRequired(request.category()),
                cleanRequired(request.description()),
                money(request.amount()),
                request.entryDate(),
                normalizeFinancialStatus(request.status()),
                clean(request.notes()),
                tenantId,
                store,
                id
        );
        auditService.recordCurrent("FINANCIAL_ENTRY_UPDATED", "financial_entries", String.valueOf(id), "Lançamento financeiro atualizado.", "ALERTA", null);
        enqueueFinancialEntryChange(tenantId, store, id, "FINANCIAL_ENTRY_UPDATED");
        return financialEntry(tenantId, store, id);
    }

    @Transactional
    public Map<String, Object> updateFinancialEntryStatus(String tenantId, String storeId, int id, FinancialEntryStatusRequest request) {
        permissionService.require(Permission.MANAGE_FINANCE);
        if (request == null || request.status() == null || request.status().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o status do lançamento.");
        }
        String store = normalizeWritableStore(tenantId, storeId);
        financialEntry(tenantId, store, id);
        String status = normalizeFinancialStatus(request.status());
        jdbcTemplate.update("""
                UPDATE financial_entries
                SET status = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, status, tenantId, store, id);
        auditService.recordCurrent("FINANCIAL_ENTRY_STATUS_UPDATED", "financial_entries", String.valueOf(id), "Status financeiro atualizado para " + status + ".", "ALERTA", request.reason());
        enqueueFinancialEntryChange(tenantId, store, id, "FINANCIAL_ENTRY_STATUS_UPDATED");
        return financialEntry(tenantId, store, id);
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

    public Map<String, Object> restoreBackupPreview(String tenantId, long id) {
        permissionService.require(Permission.RESTORE_BACKUP);
        initializer.ensureReady();
        Map<String, Object> backup = single("""
                SELECT id, tenant_id AS tenantId, store_id AS storeId, source_id AS sourceId, device_id AS deviceId,
                       status, total_rows AS totalRows, file_name AS fileName,
                       CAST(created_at AS CHAR) AS createdAt,
                       CAST(finished_at AS CHAR) AS finishedAt,
                       message
                FROM backup_runs
                WHERE tenant_id = ? AND id = ?
                """, tenantId, id);
        List<String> warnings = new ArrayList<>();
        warnings.add("A restauracao so e liberada depois de validar o arquivo fisico do backup.");
        warnings.add("Antes de restaurar, o sistema cria um snapshot de seguranca no historico.");
        warnings.add("A confirmacao exigida e literal para evitar clique acidental.");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("id", backup.get("id"));
        response.put("tenantId", backup.get("tenantId"));
        response.put("storeId", backup.get("storeId"));
        response.put("sourceId", backup.get("sourceId"));
        response.put("deviceId", backup.get("deviceId"));
        response.put("status", backup.get("status"));
        response.put("totalRows", backup.get("totalRows"));
        response.put("fileName", backup.get("fileName"));
        response.put("createdAt", backup.get("createdAt"));
        response.put("finishedAt", backup.get("finishedAt"));
        response.put("message", backup.get("message"));
        response.put("requiredConfirmation", restoreConfirmation(id));
        response.put("restoreAvailable", false);
        response.put("warnings", warnings);
        return response;
    }

    @Transactional
    public Map<String, Object> restoreBackup(String tenantId, long id, Map<String, Object> request) {
        permissionService.require(Permission.RESTORE_BACKUP);
        Map<String, Object> preview = restoreBackupPreview(tenantId, id);
        String expectedConfirmation = restoreConfirmation(id);
        String confirmation = value(request == null ? null : request.get("confirmation"));
        if (!expectedConfirmation.equals(confirmation)) {
            auditService.recordCurrent("BACKUP_RESTORE_REJECTED", "backup_runs", String.valueOf(id), "Restauracao bloqueada por confirmacao invalida.", "CRITICO", null);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Confirmacao invalida. Digite exatamente: " + expectedConfirmation);
        }
        String store = String.valueOf(preview.getOrDefault("storeId", "WEB"));
        String fileName = "pre-restore-" + tenantId + "-" + System.currentTimeMillis() + ".zip";
        jdbcTemplate.update("""
                INSERT INTO backup_runs (tenant_id, store_id, source_id, status, total_rows, file_name, finished_at, message)
                VALUES (?, ?, 'WEB', 'SAFETY_SNAPSHOT', ?, ?, CURRENT_TIMESTAMP, ?)
                """,
                tenantId,
                store,
                estimateProtectedRows(tenantId, store),
                fileName,
                "Snapshot de seguranca registrado antes da tentativa de restauracao do backup " + id + "."
        );
        Long snapshotId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        auditService.recordCurrent("BACKUP_RESTORE_BLOCKED", "backup_runs", String.valueOf(id), "Restauracao destrutiva bloqueada ate existir arquivo fisico validado.", "CRITICO", "Snapshot " + snapshotId);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", "RESTORE_BLOCKED");
        response.put("restoreExecuted", false);
        response.put("backupId", id);
        response.put("safetySnapshotId", snapshotId);
        response.put("message", "Snapshot de seguranca criado. A restauracao real foi bloqueada porque nao existe arquivo fisico de backup validado para aplicar com rollback seguro.");
        response.put("nextStep", "Anexe ou gere um backup fisico validado antes de liberar restauracao destrutiva.");
        return response;
    }

    private String restoreConfirmation(long id) {
        return "RESTAURAR BACKUP " + id;
    }

    private int estimateProtectedRows(String tenantId, String store) {
        String[] tables = {"products", "clients", "users", "sales", "sale_items", "cash_sessions", "cash_movements", "stock_movements", "financial_entries"};
        int total = 0;
        for (String table : tables) {
            Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM `" + table + "` WHERE tenant_id = ? AND store_id = ?", Integer.class, tenantId, store);
            total += value == null ? 0 : value;
        }
        return total;
    }

    private Map<String, Object> cashMovement(String tenantId, String storeId, int id, CashMovementRequest request, String type, String auditAction) {
        permissionService.require(Permission.CASH_MOVEMENT);
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo da movimentação.");
        }
        String store = normalizeWritableStore(tenantId, storeId);
        cashSession(tenantId, store, id);
        int movementId = nextScopedId("cash_movements", tenantId, store);
        jdbcTemplate.update("""
                INSERT INTO cash_movements (tenant_id, store_id, device_id, source_id, id, session_id, type, value, observation, date_time)
                VALUES (?, ?, NULL, 'WEB', ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, tenantId, store, movementId, id, type, request.value(), request.observation() == null ? request.reason() : request.observation());
        auditService.recordCurrent(auditAction, "cash_sessions", String.valueOf(id), type + " registrada.", "ALERTA", request.reason());
        enqueueCashMovementChange(tenantId, store, movementId, auditAction);
        enqueueCashSessionChange(tenantId, store, id, "CASH_SESSION_UPDATED");
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
        int movementId = nextScopedId("stock_movements", tenantId, store);
        jdbcTemplate.update("""
                INSERT INTO stock_movements
                    (tenant_id, store_id, device_id, source_id, id, product_code, type, quantity, previous_stock, new_stock, origin, reference_type, reference_id, reason, user, created_at)
                VALUES (?, ?, NULL, 'WEB', ?, ?, ?, ?, ?, ?, 'APPGESTAO', ?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, tenantId, store, movementId, productCode, type, quantity.abs(), previous, next, referenceType, referenceId, reason, currentUser());
        enqueueProductChange(tenantId, store, productCode, "PRODUCT_STOCK_CHANGED");
        enqueueStockMovementChange(tenantId, store, movementId, "STOCK_MOVEMENT_CREATED");
        return Map.of("productCode", productCode, "previousStock", previous, "newStock", next, "type", type);
    }

    private void enqueueProductChange(String tenantId, String store, String code, String operation) {
        invalidatePanelCache();
        webChangeOutboxService.enqueue(tenantId, store, "PRODUCT", code, operation,
                syncPayload("products", syncProductRecord(tenantId, store, code)));
    }

    private void enqueueClientChange(String tenantId, String store, int id, String operation) {
        invalidatePanelCache();
        webChangeOutboxService.enqueue(tenantId, store, "CLIENT", String.valueOf(id), operation,
                syncPayload("clients", syncClientRecord(tenantId, store, id)));
    }

    private void enqueueEmployeeChange(String tenantId, String store, String username, String operation) {
        invalidatePanelCache();
        webChangeOutboxService.enqueue(tenantId, store, "USER", username, operation,
                syncPayload("users", syncUserRecord(tenantId, store, username)));
    }

    private void enqueueCashSessionChange(String tenantId, String store, int id, String operation) {
        invalidatePanelCache();
        webChangeOutboxService.enqueue(tenantId, store, "CASH_SESSION", String.valueOf(id), operation,
                syncPayload("cash_sessions", syncCashSessionRecord(tenantId, store, id)));
    }

    private void enqueueCashMovementChange(String tenantId, String store, int id, String operation) {
        invalidatePanelCache();
        webChangeOutboxService.enqueue(tenantId, store, "CASH_MOVEMENT", String.valueOf(id), operation,
                syncPayload("cash_movements", syncCashMovementRecord(tenantId, store, id)));
    }

    private void enqueueSaleChange(String tenantId, String store, int id, String operation) {
        invalidatePanelCache();
        webChangeOutboxService.enqueue(tenantId, store, "SALE", String.valueOf(id), operation,
                syncPayload("sales", syncSaleRecord(tenantId, store, id)));
    }

    private void enqueueSaleCancellationChange(String tenantId, String store, int id, String operation) {
        invalidatePanelCache();
        webChangeOutboxService.enqueue(tenantId, store, "SALE_CANCELLATION", String.valueOf(id), operation,
                syncPayload("sale_cancellations", syncSaleCancellationRecord(tenantId, store, id)));
    }

    private void enqueueStockMovementChange(String tenantId, String store, int id, String operation) {
        invalidatePanelCache();
        webChangeOutboxService.enqueue(tenantId, store, "STOCK_MOVEMENT", String.valueOf(id), operation,
                syncPayload("stock_movements", syncStockMovementRecord(tenantId, store, id)));
    }

    private void enqueueFinancialEntryChange(String tenantId, String store, int id, String operation) {
        invalidatePanelCache();
        webChangeOutboxService.enqueue(tenantId, store, "FINANCIAL_ENTRY", String.valueOf(id), operation,
                syncPayload("financial_entries", syncFinancialEntryRecord(tenantId, store, id)));
    }

    private void invalidatePanelCache() {
        panelCacheService.clear();
    }

    private Map<String, Object> syncPayload(String table, Map<String, Object> record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("table", table);
        payload.put("record", record);
        return payload;
    }

    private Map<String, Object> syncProductRecord(String tenantId, String store, String code) {
        return dbRecord(single("""
                SELECT tenant_id, store_id, device_id, source_id, code, description, unit, price, cost_price,
                       stock, supplier_id, category, barcode, min_stock, ideal_stock, active, created_at, updated_at, deleted_at
                FROM products
                WHERE tenant_id = ? AND store_id = ? AND code = ?
                """, tenantId, store, code));
    }

    private Map<String, Object> syncClientRecord(String tenantId, String store, int id) {
        return dbRecord(single("""
                SELECT tenant_id, store_id, device_id, source_id, id, name, cpf_cnpj, phone, email, address,
                       created_at, birth_date, active, notes, loyalty_points, updated_at, deleted_at
                FROM clients
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, tenantId, store, id));
    }

    private Map<String, Object> syncUserRecord(String tenantId, String store, String username) {
        return dbRecord(single("""
                SELECT tenant_id, store_id, device_id, source_id, username, password, display_name, role,
                       active, created_at, updated_at, last_login_at, permissions_json
                FROM users
                WHERE tenant_id = ? AND store_id = ? AND username = ?
                """, tenantId, store, username));
    }

    private Map<String, Object> syncCashSessionRecord(String tenantId, String store, int id) {
        return dbRecord(single("""
                SELECT tenant_id, store_id, device_id, source_id, id, cash_id, operator, opening_balance,
                       closing_balance, expected_balance, difference, observation, opened_at, closed_at,
                       closed_by, close_reason, is_open, status
                FROM cash_sessions
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, tenantId, store, id));
    }

    private Map<String, Object> syncCashMovementRecord(String tenantId, String store, int id) {
        return dbRecord(single("""
                SELECT tenant_id, store_id, device_id, source_id, id, session_id, type, value, observation, date_time
                FROM cash_movements
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, tenantId, store, id));
    }

    private Map<String, Object> syncSaleRecord(String tenantId, String store, int id) {
        return dbRecord(single("""
                SELECT tenant_id, store_id, device_id, source_id, id, session_id, operator, discount,
                       surcharge, payment_method, amount_paid, status, date_time
                FROM sales
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, tenantId, store, id));
    }

    private Map<String, Object> syncSaleCancellationRecord(String tenantId, String store, int id) {
        return dbRecord(single("""
                SELECT tenant_id, store_id, device_id, source_id, id, sale_id, reason, cancelled_by, cancelled_at
                FROM sale_cancellations
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, tenantId, store, id));
    }

    private Map<String, Object> syncStockMovementRecord(String tenantId, String store, int id) {
        return dbRecord(single("""
                SELECT tenant_id, store_id, device_id, source_id, id, product_code, type, quantity,
                       previous_stock, new_stock, origin, reference_type, reference_id, reason, user, created_at
                FROM stock_movements
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, tenantId, store, id));
    }

    private Map<String, Object> syncFinancialEntryRecord(String tenantId, String store, int id) {
        return dbRecord(single("""
                SELECT tenant_id, store_id, device_id, source_id, id, type, category, description, amount,
                       entry_date, status, created_by, created_at, updated_at, origin, notes
                FROM financial_entries
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, tenantId, store, id));
    }

    private Map<String, Object> dbRecord(Map<String, Object> row) {
        Map<String, Object> record = new LinkedHashMap<>();
        row.forEach((key, value) -> record.put(key, dbValue(value)));
        return record;
    }

    private Object dbValue(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.toPlainString();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        return value;
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
        Integer baseline = jdbcTemplate.queryForObject("SELECT COALESCE(MAX(id), 0) + 1 FROM `" + table + "` WHERE tenant_id = ? AND store_id = ?", Integer.class, tenantId, storeId);
        int safeBaseline = baseline == null || baseline < 1 ? 1 : baseline;
        jdbcTemplate.update("""
                INSERT INTO scoped_sequences (tenant_id, store_id, sequence_name, next_value)
                VALUES (?, ?, ?, LAST_INSERT_ID(? + 1))
                ON DUPLICATE KEY UPDATE
                    next_value = LAST_INSERT_ID(GREATEST(next_value, VALUES(next_value) - 1) + 1),
                    updated_at = CURRENT_TIMESTAMP
                """, tenantId, storeId, table, safeBaseline);
        Long nextValue = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (nextValue == null || nextValue <= 1 || nextValue - 1 > Integer.MAX_VALUE) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Nao foi possivel reservar o proximo identificador.");
        }
        return Math.toIntExact(nextValue - 1);
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

    private Map<String, Object> productRow(Map<String, Object> row) {
        return productRow(
                String.valueOf(row.get("tenant_id")),
                String.valueOf(row.get("store_id")),
                value(row.get("source_id")),
                value(row.get("code")),
                value(row.get("description")),
                value(row.get("unit")),
                decimal(row.get("price")),
                decimal(row.get("cost_price")),
                decimal(row.get("stock")),
                decimal(row.get("min_stock")),
                decimal(row.get("ideal_stock")),
                value(row.get("category")),
                value(row.get("barcode")),
                booleanValue(row.get("active")),
                timestampText(row.get("created_at")),
                timestampText(row.get("updated_at"))
        );
    }

    private Map<String, Object> productRow(
            String tenantId,
            String storeId,
            String sourceId,
            String code,
            String description,
            String unit,
            BigDecimal price,
            BigDecimal costPrice,
            BigDecimal stock,
            BigDecimal minStock,
            BigDecimal idealStock,
            String category,
            String barcode,
            boolean active,
            String createdAt,
            String updatedAt
    ) {
        BigDecimal safePrice = money(price);
        BigDecimal safeCost = money(costPrice);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", tenantId);
        row.put("storeId", storeId);
        row.put("sourceId", sourceId);
        row.put("store", sourceId == null || sourceId.isBlank() ? storeId : sourceId);
        row.put("id", code);
        row.put("code", code);
        row.put("name", description);
        row.put("description", description);
        row.put("unit", unit);
        row.put("price", safePrice);
        row.put("costPrice", safeCost);
        row.put("marginPercent", marginPercent(safePrice, safeCost));
        row.put("stock", quantity(stock));
        row.put("currentStock", quantity(stock));
        row.put("minStock", quantity(minStock));
        row.put("minimumStock", quantity(minStock));
        row.put("idealStock", quantity(idealStock));
        row.put("category", category);
        row.put("barcode", barcode);
        row.put("active", active);
        row.put("status", active ? "Ativo" : "Inativo");
        row.put("createdAt", createdAt);
        row.put("updatedAt", updatedAt);
        return row;
    }

    private Map<String, Object> clientRow(Map<String, Object> row) {
        return clientRow(
                String.valueOf(row.get("tenant_id")),
                String.valueOf(row.get("store_id")),
                value(row.get("source_id")),
                ((Number) row.get("id")).intValue(),
                value(row.get("name")),
                value(row.get("cpf_cnpj")),
                value(row.get("phone")),
                value(row.get("email")),
                value(row.get("address")),
                timestampText(row.get("birth_date")),
                booleanValue(row.get("active")),
                value(row.get("notes")),
                row.get("loyalty_points") instanceof Number number ? number.intValue() : 0,
                timestampText(row.get("created_at")),
                timestampText(row.get("updated_at"))
        );
    }

    private Map<String, Object> clientRow(
            String tenantId,
            String storeId,
            String sourceId,
            int id,
            String name,
            String cpfCnpj,
            String phone,
            String email,
            String address,
            String birthDate,
            boolean active,
            String notes,
            int loyaltyPoints,
            String createdAt,
            String updatedAt
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", tenantId);
        row.put("storeId", storeId);
        row.put("sourceId", sourceId);
        row.put("store", sourceId == null || sourceId.isBlank() ? storeId : sourceId);
        row.put("id", id);
        row.put("name", name);
        row.put("cpfCnpj", cpfCnpj);
        row.put("phone", phone);
        row.put("email", email);
        row.put("address", address);
        row.put("birthDate", birthDate);
        row.put("active", active);
        row.put("status", active ? "Ativo" : "Inativo");
        row.put("notes", notes);
        row.put("loyaltyPoints", loyaltyPoints);
        row.put("createdAt", createdAt);
        row.put("updatedAt", updatedAt);
        return row;
    }

    private Map<String, Object> financialEntryRow(Map<String, Object> row) {
        Object entryDate = row.get("entry_date");
        LocalDate date = entryDate instanceof java.sql.Date sqlDate ? sqlDate.toLocalDate() : (entryDate == null ? null : LocalDate.parse(String.valueOf(entryDate)));
        return financialEntryRow(
                String.valueOf(row.get("tenant_id")),
                String.valueOf(row.get("store_id")),
                value(row.get("source_id")),
                Integer.parseInt(String.valueOf(row.get("id"))),
                value(row.get("type")),
                value(row.get("category")),
                value(row.get("description")),
                decimal(row.get("amount")),
                date,
                value(row.get("status")),
                value(row.get("created_by")),
                timestampText(row.get("created_at")),
                timestampText(row.get("updated_at")),
                value(row.get("origin")),
                value(row.get("notes"))
        );
    }

    private Map<String, Object> financialEntryRow(
            String tenantId,
            String storeId,
            String sourceId,
            int id,
            String type,
            String category,
            String description,
            BigDecimal amount,
            LocalDate entryDate,
            String status,
            String createdBy,
            String createdAt,
            String updatedAt,
            String origin,
            String notes
    ) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenantId", tenantId);
        row.put("storeId", storeId);
        row.put("store", storeId);
        row.put("sourceId", sourceId);
        row.put("id", id);
        row.put("type", type);
        row.put("category", category);
        row.put("description", description);
        row.put("amount", money(amount));
        row.put("entryDate", entryDate == null ? null : entryDate.toString());
        row.put("status", status);
        row.put("createdBy", createdBy);
        row.put("createdAt", createdAt);
        row.put("updatedAt", updatedAt);
        row.put("origin", origin);
        row.put("notes", notes);
        return row;
    }

    private void validateProductRequest(ProductRequest request, boolean allowStock) {
        if (request == null || request.description() == null || request.description().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o nome do produto.");
        }
        if (request.code() == null || request.code().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o codigo do produto.");
        }
        if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Preco de venda deve ser maior ou igual a zero.");
        }
        if (request.costPrice() != null && request.costPrice().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Preco de custo deve ser maior ou igual a zero.");
        }
        if (request.minStock() != null && request.minStock().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estoque minimo deve ser maior ou igual a zero.");
        }
        if (allowStock && request.stock() != null && request.stock().compareTo(BigDecimal.ZERO) < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estoque inicial deve ser maior ou igual a zero.");
        }
    }

    private void validateClientRequest(ClientRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o nome do cliente.");
        }
        if (request.id() != null && request.id() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Codigo do cliente deve ser positivo.");
        }
        if (request.email() != null && !request.email().isBlank() && !request.email().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email invalido.");
        }
        if (request.cpfCnpj() != null && !request.cpfCnpj().isBlank()) {
            String digits = request.cpfCnpj().replaceAll("\\D", "");
            if (digits.length() != 11 && digits.length() != 14) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CPF/CNPJ invalido.");
            }
        }
    }

    private void validateFinancialEntry(FinancialEntryRequest request) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o lançamento financeiro.");
        }
        normalizeFinancialType(request.type());
        normalizeFinancialStatus(request.status());
        cleanRequired(request.category());
        cleanRequired(request.description());
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valor deve ser maior que zero.");
        }
        if (request.entryDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a data do lançamento.");
        }
    }

    private String normalizeFinancialType(String type) {
        String value = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "RECEITA", "ENTRADA", "INCOME" -> "RECEITA";
            case "DESPESA", "SAIDA", "EXPENSE" -> "DESPESA";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo financeiro invalido.");
        };
    }

    private String normalizeFinancialTypeOrNull(String type) {
        if (type == null || type.isBlank() || "all".equalsIgnoreCase(type)) {
            return null;
        }
        return normalizeFinancialType(type);
    }

    private String normalizeFinancialStatus(String status) {
        if (status == null || status.isBlank()) {
            return "PENDENTE";
        }
        String value = status.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "PAGO", "PAID" -> "PAGO";
            case "PENDENTE", "PENDING" -> "PENDENTE";
            case "CANCELADO", "CANCELLED", "CANCELED" -> "CANCELADO";
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status financeiro invalido.");
        };
    }

    private String normalizeFinancialStatusOrNull(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status)) {
            return null;
        }
        return normalizeFinancialStatus(status);
    }

    private String financialPeriodClause(String period, String tenantId, String store, List<Object> args) {
        String value = period == null ? "today" : period.trim().toLowerCase(Locale.ROOT);
        if ("all".equals(value)) {
            return "";
        }
        LocalDate anchor = switch (value) {
            case "7d", "7", "week", "month", "mes", "mês", "30d", "30dias", "30 dias", "year", "ano" -> financialAnchorDate(tenantId, store);
            default -> LocalDate.now();
        };
        LocalDate start = switch (value) {
            case "7d", "7", "week" -> anchor.minusDays(6);
            case "month", "mes", "mês", "30d", "30dias", "30 dias" -> anchor.minusDays(29);
            case "year", "ano" -> anchor.minusDays(364);
            default -> anchor;
        };
        args.add(start);
        args.add(anchor.plusDays(1));
        return " AND entry_date >= ? AND entry_date < ?";
    }

    private LocalDate financialAnchorDate(String tenantId, String store) {
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        StringBuilder sql = new StringBuilder("SELECT MAX(entry_date) FROM financial_entries WHERE tenant_id = ?");
        if (store != null) {
            sql.append(" AND store_id = ?");
            args.add(store);
        }
        java.sql.Date value = jdbcTemplate.queryForObject(sql.toString(), java.sql.Date.class, args.toArray());
        return value == null ? LocalDate.now() : value.toLocalDate();
    }

    private String resolvedPassword(EmployeeRequest request, boolean required) {
        String plain = request.password();
        if (plain != null && !plain.isBlank()) {
            if (plain.trim().length() < 10) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Senha deve ter ao menos 10 caracteres.");
            }
            return BCrypt.hashpw(plain, BCrypt.gensalt());
        }
        String hashed = request.passwordHash();
        if (hashed != null && !hashed.isBlank()) {
            String value = hashed.trim();
            if (value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$")) {
                return value;
            }
        }
        if (required) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe a senha do usuario.");
        }
        return null;
    }

    private List<String> permissionsFrom(Object value) {
        if (value == null) {
            return List.of();
        }
        String text = String.valueOf(value).trim();
        if (text.isBlank()) {
            return List.of();
        }
        try {
            return sanitizePermissions(JSON.readValue(text, new TypeReference<List<String>>() {
            }));
        } catch (Exception ignored) {
            String cleaned = text.replace("[", "").replace("]", "");
            List<String> permissions = new ArrayList<>();
            for (String item : cleaned.split(",")) {
                String permission = item.trim().replace("\"", "");
                if (!permission.isBlank()) {
                    permissions.add(permission);
                }
            }
            return sanitizePermissions(permissions);
        }
    }

    private String permissionsJson(List<String> permissions) {
        try {
            return JSON.writeValueAsString(sanitizePermissions(permissions));
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Permissoes invalidas.");
        }
    }

    private List<String> sanitizePermissions(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        List<String> sanitized = new ArrayList<>();
        for (String permission : permissions) {
            if (permission == null || permission.isBlank()) {
                continue;
            }
            String value = permission.trim().toLowerCase(Locale.ROOT);
            if (!sanitized.contains(value)) {
                sanitized.add(value);
            }
        }
        return sanitized;
    }

    private void guardLastActiveAdmin(String tenantId, String username) {
        Map<String, Object> user = employee(tenantId, username);
        if (!isAdminRole(String.valueOf(user.get("role")))) {
            return;
        }
        Integer activeAdmins = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM users
                WHERE tenant_id = ? AND active = TRUE AND UPPER(role) IN ('DONO', 'OWNER', 'ADMIN', 'ADMINISTRADOR', 'ADMINISTRATOR')
                """, Integer.class, tenantId);
        if (activeAdmins != null && activeAdmins <= 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nao e permitido inativar o ultimo ADMIN ativo.");
        }
    }

    private boolean isAdminRole(String role) {
        String value = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return value.equals("DONO") || value.equals("OWNER") || value.equals("ADMIN") || value.equals("ADMINISTRADOR") || value.equals("ADMINISTRATOR");
    }

    private boolean isActiveStatus(String status) {
        String value = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return value.equals("active") || value.equals("ativo") || value.equals("true") || value.equals("1");
    }

    private int safeLimit(int limit, int fallback, int max) {
        if (limit <= 0) {
            return fallback;
        }
        return Math.min(limit, max);
    }

    private int safeOffset(int offset) {
        return Math.max(0, offset);
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal quantity(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(3, RoundingMode.HALF_UP);
    }

    private BigDecimal marginPercent(BigDecimal price, BigDecimal cost) {
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0 || cost == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return price.subtract(cost).multiply(BigDecimal.valueOf(100)).divide(price, 2, RoundingMode.HALF_UP);
    }

    private String productUnit(String unit) {
        return unit == null || unit.isBlank() ? "UN" : unit.trim().toUpperCase(Locale.ROOT);
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String cleanRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Campo obrigatorio nao informado.");
        }
        return value.trim();
    }

    private String value(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean booleanValue(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)) || "1".equals(String.valueOf(value));
    }

    private String timestampText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String currentUser() {
        return AuthContext.current().map(AuthTokenService.SessionToken::username).orElse("sistema");
    }

    private String normalizeListStore(String storeId) {
        if (storeId == null || storeId.isBlank() || "all".equalsIgnoreCase(storeId)) {
            return AuthContext.current()
                    .filter(session -> !canAccessAllStores(session))
                    .map(AuthTokenService.SessionToken::storeId)
                    .orElse(null);
        }
        return requireSessionStoreAccess(storeId.trim());
    }

    private String normalizeWritableStore(String tenantId, String storeId) {
        AuthContext.current().ifPresent(session -> {
            if (!tenantId.equals(session.tenantId())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Tenant da sessão não corresponde à operação.");
            }
        });
        return normalizeStore(storeId);
    }

    private String normalizeStore(String storeId) {
        String store = storeId == null || storeId.isBlank() || "all".equalsIgnoreCase(storeId) ? "WEB" : storeId.trim();
        return requireSessionStoreAccess(store);
    }

    private String requireSessionStoreAccess(String store) {
        AuthTokenService.SessionToken session = AuthContext.current().orElse(null);
        if (session == null || canAccessAllStores(session)) {
            return store;
        }
        if (!store.equals(session.storeId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario nao autorizado para esta loja.");
        }
        return store;
    }

    private boolean canAccessAllStores(AuthTokenService.SessionToken session) {
        if (session == null) {
            return true;
        }
        String role = session.role() == null ? "" : session.role().trim().toUpperCase(Locale.ROOT);
        boolean tenantWideRole = role.equals("DONO") || role.equals("OWNER") || role.equals("ADMIN") || role.equals("ADMINISTRADOR") || role.equals("ADMINISTRATOR");
        String store = session.storeId() == null ? "" : session.storeId().trim();
        return tenantWideRole && (store.isBlank() || "WEB".equalsIgnoreCase(store) || "all".equalsIgnoreCase(store));
    }
}
