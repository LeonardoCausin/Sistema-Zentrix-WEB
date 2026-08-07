package br.com.zentrix.web.service;

import br.com.zentrix.web.dto.ActivationCodeRequest;
import br.com.zentrix.web.dto.ProvisionTenantRequest;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ZentrixAdminService {
    private static final List<PlanDefinition> PLANS = List.of(
            new PlanDefinition("BASICO", "Basico", bd("99.90"), 1, 0, bd("49.90"), bd("29.90"), false, "Acesso somente ao PDV."),
            new PlanDefinition("INTERMEDIARIO", "Intermediario", bd("169.90"), 1, 1, bd("49.90"), bd("29.90"), true, "PDV + AppGestao essencial."),
            new PlanDefinition("PRO", "Pro", bd("269.90"), 2, 2, bd("49.90"), bd("29.90"), true, "Gestao completa por loja.")
    );

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final ProvisioningService provisioningService;
    private final PanelCacheService panelCacheService;
    private final AuditService auditService;

    public ZentrixAdminService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            ProvisioningService provisioningService,
            PanelCacheService panelCacheService,
            AuditService auditService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.provisioningService = provisioningService;
        this.panelCacheService = panelCacheService;
        this.auditService = auditService;
    }

    public Map<String, Object> overview() {
        initializer.ensureReady();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("clients", number("SELECT COUNT(*) FROM tenants"));
        response.put("activeClients", number("SELECT COUNT(*) FROM tenants WHERE UPPER(status) = 'ACTIVE'"));
        response.put("blockedClients", number("SELECT COUNT(*) FROM tenants WHERE UPPER(status) IN ('BLOCKED', 'SUSPENDED')"));
        response.put("activeSubscriptions", number("""
                SELECT COUNT(*)
                FROM licenses l
                JOIN (
                    SELECT tenant_id, MAX(id) AS id
                    FROM licenses
                    GROUP BY tenant_id
                ) latest ON latest.id = l.id
                WHERE UPPER(l.status) = 'ACTIVE'
                """));
        response.put("expiringSoon", number("""
                SELECT COUNT(*)
                FROM licenses l
                JOIN (
                    SELECT tenant_id, MAX(id) AS id
                    FROM licenses
                    GROUP BY tenant_id
                ) latest ON latest.id = l.id
                WHERE UPPER(l.status) = 'ACTIVE'
                  AND l.expires_at IS NOT NULL
                  AND l.expires_at BETWEEN NOW() AND DATE_ADD(NOW(), INTERVAL 7 DAY)
                """));
        response.put("stores", number("SELECT COUNT(*) FROM tenant_stores"));
        response.put("devices", number("SELECT COUNT(*) FROM tenant_devices"));
        response.put("pendingInvoices", number("SELECT COUNT(*) FROM billing_invoices WHERE UPPER(status) IN ('PENDING', 'OVERDUE')"));
        response.put("deadWebhooks", number("SELECT COUNT(*) FROM billing_webhook_queue WHERE UPPER(status) IN ('FAILED', 'DEAD')"));
        response.put("failedNotifications", number("SELECT COUNT(*) FROM billing_notifications WHERE UPPER(status) = 'FAILED'"));
        response.put("recentClients", clients("", "all", 8));
        response.put("expirationAlerts", expirationAlerts());
        response.put("plans", plans());
        return response;
    }

    public List<Map<String, Object>> plans() {
        return PLANS.stream().map(plan -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("code", plan.code());
            row.put("name", plan.name());
            row.put("monthlyStorePrice", plan.monthlyStorePrice());
            row.put("includedPdvPerStore", plan.includedPdvPerStore());
            row.put("includedAppGestaoPerStore", plan.includedAppGestaoPerStore());
            row.put("extraPdvPrice", plan.extraPdvPrice());
            row.put("extraAppGestaoPrice", plan.extraAppGestaoPrice());
            row.put("appGestaoIncluded", plan.appGestaoIncluded());
            row.put("maxStores", 1);
            row.put("maxDevices", plan.includedPdvPerStore() + plan.includedAppGestaoPerStore());
            row.put("description", plan.description());
            row.put("features", switch (plan.code()) {
                case "BASICO" -> List.of("PDV", "Vendas offline", "Sincronizacao e backup");
                case "INTERMEDIARIO" -> List.of("Tudo do Basico", "AppGestao", "Estoque e financeiro", "Relatorios operacionais");
                default -> List.of("Tudo do Intermediario", "Auditoria avancada", "Indicadores completos", "Suporte prioritario");
            });
            return row;
        }).toList();
    }

    public List<Map<String, Object>> expirationAlerts() {
        initializer.ensureReady();
        return jdbcTemplate.queryForList("""
                SELECT t.id AS tenantId, t.name, l.plan_name AS planName, l.expires_at AS expiresAt,
                       DATEDIFF(DATE(l.expires_at), CURRENT_DATE()) AS daysLeft
                FROM licenses l
                JOIN (
                    SELECT tenant_id, MAX(id) AS id
                    FROM licenses
                    GROUP BY tenant_id
                ) latest ON latest.id = l.id
                JOIN tenants t ON t.id = l.tenant_id
                WHERE UPPER(l.status) = 'ACTIVE'
                  AND l.expires_at IS NOT NULL
                  AND DATEDIFF(DATE(l.expires_at), CURRENT_DATE()) IN (1, 3, 7)
                ORDER BY l.expires_at ASC, t.name
                """);
    }

    public List<Map<String, Object>> clients(String search, String status, int limit) {
        initializer.ensureReady();
        String q = "%" + text(search).toLowerCase() + "%";
        String normalizedStatus = text(status);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT t.id AS tenantId, t.name, t.document, t.billing_email AS billingEmail,
                       t.billing_phone AS billingPhone, t.billing_address AS billingAddress, t.status,
                       t.block_reason AS blockReason, t.blocked_at AS blockedAt, t.blocked_by AS blockedBy,
                       t.created_at AS createdAt, t.updated_at AS updatedAt,
                       l.id AS licenseId, l.plan_name AS planName, l.status AS licenseStatus,
                       l.starts_at AS startsAt, l.expires_at AS expiresAt, l.max_stores AS maxStores, l.max_devices AS maxDevices,
                       COALESCE(stores.count, 0) AS stores,
                       COALESCE(devices.count, 0) AS devices,
                       COALESCE(users.count, 0) AS users
                FROM tenants t
                LEFT JOIN (
                    SELECT l1.*
                    FROM licenses l1
                    JOIN (
                        SELECT tenant_id, MAX(id) AS id
                        FROM licenses
                        GROUP BY tenant_id
                    ) latest ON latest.id = l1.id
                ) l ON l.tenant_id = t.id
                LEFT JOIN (SELECT tenant_id, COUNT(*) AS count FROM tenant_stores GROUP BY tenant_id) stores ON stores.tenant_id = t.id
                LEFT JOIN (SELECT tenant_id, COUNT(*) AS count FROM tenant_devices GROUP BY tenant_id) devices ON devices.tenant_id = t.id
                LEFT JOIN (SELECT tenant_id, COUNT(*) AS count FROM users GROUP BY tenant_id) users ON users.tenant_id = t.id
                WHERE (? = '' OR LOWER(t.name) LIKE ? OR LOWER(COALESCE(t.document, '')) LIKE ? OR LOWER(t.id) LIKE ?)
                  AND (? = 'all' OR UPPER(t.status) = UPPER(?))
                ORDER BY t.created_at DESC, t.name
                LIMIT ?
                """, q.equals("%%") ? "" : q, q, q, q, normalizedStatus.isBlank() ? "all" : normalizedStatus, normalizedStatus, safeLimit(limit));
        rows.forEach(row -> addBilling(row, String.valueOf(row.get("tenantId")), String.valueOf(row.getOrDefault("planName", "BASICO"))));
        return rows;
    }

    public Map<String, Object> client(String tenantId) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        List<Map<String, Object>> tenants = jdbcTemplate.queryForList("""
                SELECT id AS tenantId, name, document, billing_email AS billingEmail,
                       billing_phone AS billingPhone, billing_address AS billingAddress,
                       status, block_reason AS blockReason,
                       blocked_at AS blockedAt, blocked_by AS blockedBy,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM tenants
                WHERE id = ?
                """, tenant);
        if (tenants.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente nao encontrado.");
        }
        Map<String, Object> response = new LinkedHashMap<>(tenants.get(0));
        String latestPlan = "BASICO";
        response.put("licenses", jdbcTemplate.queryForList("""
                SELECT id, plan_name AS planName, status, starts_at AS startsAt, expires_at AS expiresAt,
                       max_stores AS maxStores, max_devices AS maxDevices, created_at AS createdAt, updated_at AS updatedAt
                FROM licenses
                WHERE tenant_id = ?
                ORDER BY id DESC
                """, tenant));
        List<Map<String, Object>> licenses = castRows(response.get("licenses"));
        if (!licenses.isEmpty()) {
            latestPlan = String.valueOf(licenses.get(0).getOrDefault("planName", "BASICO"));
        }
        response.put("stores", jdbcTemplate.queryForList("""
                SELECT id, name, source_id AS sourceId, status, block_reason AS blockReason,
                       blocked_at AS blockedAt, blocked_by AS blockedBy,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM tenant_stores
                WHERE tenant_id = ?
                ORDER BY created_at DESC, name
                """, tenant));
        response.put("devices", jdbcTemplate.queryForList("""
                SELECT store_id AS storeId, id, name, source_id AS sourceId, app_type AS appType,
                       status, billable, activated_at AS activatedAt, deactivated_at AS deactivatedAt,
                       last_seen_at AS lastSeenAt, created_at AS createdAt, updated_at AS updatedAt
                FROM tenant_devices
                WHERE tenant_id = ?
                ORDER BY last_seen_at DESC, created_at DESC
                """, tenant));
        response.put("activationCodes", jdbcTemplate.queryForList("""
                SELECT code, store_id AS storeId, store_name AS storeName, source_id AS sourceId, status,
                       expires_at AS expiresAt, used_at AS usedAt, used_device_id AS usedDeviceId, created_at AS createdAt
                FROM activation_codes
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 20
                """, tenant));
        response.put("invoices", jdbcTemplate.queryForList("""
                SELECT id, provider, provider_payment_id AS providerPaymentId, plan_name AS planName,
                       amount, due_date AS dueDate, status, checkout_url AS checkoutUrl,
                       paid_at AS paidAt, created_at AS createdAt
                FROM billing_invoices
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 30
                """, tenant));
        response.put("supportNotes", supportNotes(tenant));
        response.put("billing", billingSummary(tenant, latestPlan));
        return response;
    }

    @Transactional
    public Map<String, Object> createClient(Map<String, Object> request) {
        initializer.ensureReady();
        request = request == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request);
        String companyName = required(value(request, "companyName"), "companyName");
        String document = normalizedCpfCnpj(value(request, "document"));
        String adminUsername = required(value(request, "adminUsername"), "adminUsername");
        String adminPassword = required(value(request, "adminPassword"), "adminPassword");
        Map<String, Object> created = provisioningService.bootstrap(new ProvisionTenantRequest(
                companyName,
                document,
                defaultValue(value(request, "storeName"), "Loja matriz"),
                value(request, "sourceId"),
                value(request, "deviceId"),
                value(request, "deviceName"),
                adminUsername,
                defaultValue(value(request, "adminDisplayName"), adminUsername),
                adminPassword,
                null
        ));
        String tenantId = String.valueOf(created.get("tenantId"));
        jdbcTemplate.update("""
                UPDATE tenants
                SET billing_email = NULLIF(?, ''), billing_phone = NULLIF(?, ''), billing_address = NULLIF(?, '')
                WHERE id = ?
                """, value(request, "billingEmail"), value(request, "billingPhone"), value(request, "billingAddress"), tenantId);
        int trialDays = intValue(request == null ? null : request.get("trialDays"), 0);
        if (trialDays > 0) {
            request.put("licenseStatus", "TRIAL");
            request.put("expiresAt", LocalDateTime.now().plusDays(Math.min(trialDays, 90)).toString());
        }
        Map<String, Object> license = createLicense(tenantId, request);
        auditService.recordCurrent("ZENTRIX_ADMIN_CLIENT_CREATED", "tenants", tenantId,
                "Cliente criado pelo painel administrativo Zentrix.", "ALERTA", value(request, "reason"));
        panelCacheService.clear();
        return Map.of("client", created, "license", license);
    }

    @Transactional
    public Map<String, Object> updateBillingProfile(String tenantId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        ensureTenantExists(tenant);
        String name = required(value(request, "name"), "name");
        String document = normalizedCpfCnpj(value(request, "document"));
        String email = value(request, "billingEmail");
        String phone = value(request, "billingPhone");
        String address = value(request, "billingAddress");
        jdbcTemplate.update("""
                UPDATE tenants
                SET name = ?, document = ?, billing_email = NULLIF(?, ''),
                    billing_phone = NULLIF(?, ''), billing_address = NULLIF(?, '')
                WHERE id = ?
                """, name, document, email, phone, address, tenant);
        auditService.recordCurrent("ZENTRIX_ADMIN_BILLING_PROFILE_UPDATED", "tenants", tenant,
                "Dados de cobranca atualizados pelo painel administrativo.", "ALERTA", value(request, "reason"));
        panelCacheService.clear();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenantId", tenant);
        response.put("name", name);
        response.put("document", document);
        response.put("billingEmail", email);
        response.put("billingPhone", phone);
        response.put("billingAddress", address);
        return response;
    }

    static boolean validCpfCnpj(String value) {
        String digits = value == null ? "" : value.replaceAll("\\D", "");
        if ((digits.length() != 11 && digits.length() != 14) || digits.chars().distinct().count() == 1) {
            return false;
        }
        if (digits.length() == 11) {
            int first = checkDigit(digits.substring(0, 9), new int[]{10, 9, 8, 7, 6, 5, 4, 3, 2});
            int second = checkDigit(digits.substring(0, 9) + first, new int[]{11, 10, 9, 8, 7, 6, 5, 4, 3, 2});
            return digits.endsWith("" + first + second);
        }
        int first = checkDigit(digits.substring(0, 12), new int[]{5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        int second = checkDigit(digits.substring(0, 12) + first, new int[]{6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2});
        return digits.endsWith("" + first + second);
    }

    private static int checkDigit(String digits, int[] weights) {
        int sum = 0;
        for (int index = 0; index < weights.length; index++) {
            sum += Character.digit(digits.charAt(index), 10) * weights[index];
        }
        int result = 11 - (sum % 11);
        return result >= 10 ? 0 : result;
    }

    private String normalizedCpfCnpj(String value) {
        String document = required(value, "document").replaceAll("\\D", "");
        if (!validCpfCnpj(document)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe um CPF ou CNPJ valido.");
        }
        return document;
    }

    @Transactional
    public Map<String, Object> createLicense(String tenantId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        String plan = normalizePlan(defaultValue(value(request, "planName"), "BASICO"));
        PlanDefinition planDefinition = planDefinition(plan);
        String status = defaultValue(value(request, "licenseStatus"), defaultValue(value(request, "status"), "ACTIVE")).toUpperCase();
        if (!validStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status de assinatura invalido.");
        }
        Timestamp startsAt = timestampOrNull(value(request, "startsAt"));
        if (startsAt == null) {
            startsAt = Timestamp.valueOf(LocalDateTime.now());
        }
        Timestamp expiresAt = timestampOrNull(value(request, "expiresAt"));
        int maxStores = intValue(request == null ? null : request.get("maxStores"), 1);
        int maxDevices = intValue(request == null ? null : request.get("maxDevices"), planDefinition.includedPdvPerStore() + planDefinition.includedAppGestaoPerStore());
        Map<String, Object> previous = jdbcTemplate.queryForList("""
                SELECT plan_name AS planName FROM licenses WHERE tenant_id = ? ORDER BY id DESC LIMIT 1
                """, tenant).stream().findFirst().orElse(Map.of());
        jdbcTemplate.update("""
                INSERT INTO licenses (tenant_id, plan_name, status, starts_at, expires_at, max_stores, max_devices)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, tenant, plan, status, startsAt, expiresAt, maxStores, maxDevices);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String previousPlan = String.valueOf(previous.getOrDefault("planName", ""));
        if (!previousPlan.isBlank() && !previousPlan.equalsIgnoreCase(plan)) {
            jdbcTemplate.update("""
                    INSERT INTO billing_plan_changes
                        (tenant_id, from_plan, to_plan, effective_at, status, requested_by, reason)
                    VALUES (?, ?, ?, ?, 'APPLIED', ?, ?)
                    """, tenant, previousPlan, plan, startsAt,
                    AuthContext.current().map(AuthTokenService.SessionToken::username).orElse("zentrix-admin"), value(request, "reason"));
        }
        if (boolValue(request == null ? null : request.get("activateClient")) && "ACTIVE".equalsIgnoreCase(status)) {
            jdbcTemplate.update("""
                    UPDATE tenants
                    SET status = 'ACTIVE', block_reason = NULL, blocked_at = NULL, blocked_by = NULL
                    WHERE id = ?
                    """, tenant);
        }
        auditService.recordCurrent("ZENTRIX_ADMIN_LICENSE_CREATED", "licenses", String.valueOf(id),
                "Assinatura criada/renovada pelo painel administrativo Zentrix.", "ALERTA", value(request, "reason"));
        panelCacheService.clear();
        return Map.of(
                "id", id == null ? 0 : id,
                "tenantId", tenant,
                "planName", plan,
                "status", status,
                "startsAt", startsAt.toString(),
                "expiresAt", expiresAt == null ? "" : expiresAt.toString(),
                "maxStores", maxStores,
                "maxDevices", maxDevices,
                "billing", billingSummary(tenant, plan)
        );
    }

    public Map<String, Object> billingSummary(String tenantId, String planName) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        PlanDefinition plan = planDefinition(normalizePlan(defaultValue(planName, "BASICO")));
        long activeStores = number("""
                SELECT COUNT(*)
                FROM tenant_stores
                WHERE tenant_id = ? AND UPPER(COALESCE(status, 'ACTIVE')) = 'ACTIVE'
                """, tenant);
        activeStores = Math.max(activeStores, 1);
        long pdvApps = number("""
                SELECT COUNT(*)
                FROM tenant_devices
                WHERE tenant_id = ?
                  AND UPPER(COALESCE(status, 'ACTIVE')) = 'ACTIVE'
                  AND billable = TRUE
                  AND UPPER(COALESCE(app_type, 'PDV')) = 'PDV'
                """, tenant);
        long appGestaoApps = number("""
                SELECT COUNT(DISTINCT username)
                FROM users
                WHERE tenant_id = ?
                  AND active = TRUE
                  AND UPPER(COALESCE(role, '')) IN ('ADMIN', 'ADMINISTRADOR', 'ADMINISTRATOR', 'DONO', 'OWNER', 'MASTER_ADMIN', 'MASTERADMIN', 'SUPER_ADMIN', 'SUPERADMIN')
                """, tenant);
        long includedPdv = activeStores * plan.includedPdvPerStore();
        long includedAppGestao = activeStores * plan.includedAppGestaoPerStore();
        long extraPdv = Math.max(pdvApps - includedPdv, 0);
        long extraAppGestao = plan.appGestaoIncluded() ? Math.max(appGestaoApps - includedAppGestao, 0) : 0;
        BigDecimal storeSubtotal = plan.monthlyStorePrice().multiply(BigDecimal.valueOf(activeStores));
        BigDecimal extraPdvSubtotal = plan.extraPdvPrice().multiply(BigDecimal.valueOf(extraPdv));
        BigDecimal extraAppGestaoSubtotal = plan.extraAppGestaoPrice().multiply(BigDecimal.valueOf(extraAppGestao));
        BigDecimal total = storeSubtotal.add(extraPdvSubtotal).add(extraAppGestaoSubtotal);

        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("planCode", plan.code());
        summary.put("planName", plan.name());
        summary.put("monthlyStorePrice", plan.monthlyStorePrice());
        summary.put("activeStores", activeStores);
        summary.put("storeSubtotal", storeSubtotal);
        summary.put("pdvApps", pdvApps);
        summary.put("includedPdvApps", includedPdv);
        summary.put("extraPdvApps", extraPdv);
        summary.put("extraPdvPrice", plan.extraPdvPrice());
        summary.put("extraPdvSubtotal", extraPdvSubtotal);
        summary.put("appGestaoApps", appGestaoApps);
        summary.put("includedAppGestaoApps", includedAppGestao);
        summary.put("extraAppGestaoApps", extraAppGestao);
        summary.put("extraAppGestaoPrice", plan.extraAppGestaoPrice());
        summary.put("extraAppGestaoSubtotal", extraAppGestaoSubtotal);
        summary.put("appGestaoIncluded", plan.appGestaoIncluded());
        summary.put("monthlyTotal", total);
        return summary;
    }

    public List<Map<String, Object>> clientHistory(String tenantId) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        ensureTenantExists(tenant);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.addAll(jdbcTemplate.queryForList("""
                SELECT 'Assinatura' AS type, CONCAT('Plano ', plan_name, ' - ', status) AS title,
                       CONCAT('Limites: ', max_stores, ' loja(s) / ', max_devices, ' PDV(s)') AS description,
                       created_at AS createdAt
                FROM licenses
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 20
                """, tenant));
        rows.addAll(jdbcTemplate.queryForList("""
                SELECT 'Auditoria' AS type, acao AS title, COALESCE(details, reason, '-') AS description,
                       created_at AS createdAt
                FROM audit_log
                WHERE entity_id = ?
                   OR details LIKE ?
                   OR (entity_type = 'tenant_stores' AND entity_id IN (
                       SELECT id FROM tenant_stores WHERE tenant_id = ?
                   ))
                ORDER BY created_at DESC, id DESC
                LIMIT 20
                """, tenant, "%" + tenant + "%", tenant));
        rows.sort((left, right) -> String.valueOf(right.get("createdAt")).compareTo(String.valueOf(left.get("createdAt"))));
        return rows.stream().limit(30).toList();
    }

    public Map<String, Object> clientHealth(String tenantId) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        ensureTenantExists(tenant);
        List<Map<String, Object>> stores = jdbcTemplate.queryForList("""
                SELECT ts.id AS storeId, ts.name, ts.source_id AS sourceId, ts.status,
                       ts.block_reason AS blockReason, ts.blocked_at AS blockedAt, ts.blocked_by AS blockedBy,
                       (SELECT sr.received_at FROM sync_runs sr WHERE sr.tenant_id = ts.tenant_id AND sr.store_id = ts.id ORDER BY sr.received_at DESC, sr.id DESC LIMIT 1) AS lastSyncAt,
                       (SELECT sr.status FROM sync_runs sr WHERE sr.tenant_id = ts.tenant_id AND sr.store_id = ts.id ORDER BY sr.received_at DESC, sr.id DESC LIMIT 1) AS lastSyncStatus,
                       (SELECT br.created_at FROM backup_runs br WHERE br.tenant_id = ts.tenant_id AND br.store_id = ts.id ORDER BY br.created_at DESC, br.id DESC LIMIT 1) AS lastBackupAt,
                       (SELECT br.status FROM backup_runs br WHERE br.tenant_id = ts.tenant_id AND br.store_id = ts.id ORDER BY br.created_at DESC, br.id DESC LIMIT 1) AS lastBackupStatus,
                       (SELECT COUNT(*) FROM tenant_devices td WHERE td.tenant_id = ts.tenant_id AND td.store_id = ts.id AND UPPER(COALESCE(td.status, 'ACTIVE')) = 'ACTIVE' AND UPPER(COALESCE(td.app_type, 'PDV')) = 'PDV') AS activeDevices,
                       (SELECT MAX(td.last_seen_at) FROM tenant_devices td WHERE td.tenant_id = ts.tenant_id AND td.store_id = ts.id) AS lastDeviceSeenAt
                FROM tenant_stores ts
                WHERE ts.tenant_id = ?
                ORDER BY ts.name
                """, tenant);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("tenantId", tenant);
        response.put("stores", stores);
        response.put("storeCount", stores.size());
        response.put("activeDevices", stores.stream().mapToLong(row -> longValue(row.get("activeDevices"))).sum());
        response.put("storesWithoutSync", stores.stream().filter(row -> row.get("lastSyncAt") == null).count());
        response.put("storesWithoutBackup", stores.stream().filter(row -> row.get("lastBackupAt") == null).count());
        return response;
    }

    public Map<String, Object> financeOverview(String status, int limit) {
        initializer.ensureReady();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("receivedThisMonth", decimal("""
                SELECT COALESCE(SUM(amount), 0) FROM billing_invoices
                WHERE paid_at >= DATE_FORMAT(CURRENT_DATE(), '%Y-%m-01')
                  AND UPPER(status) IN ('RECEIVED', 'CONFIRMED')
                """));
        response.put("pendingAmount", decimal("""
                SELECT COALESCE(SUM(amount), 0) FROM billing_invoices
                WHERE UPPER(status) IN ('PENDING', 'AWAITING_RISK_ANALYSIS')
                """));
        response.put("overdueAmount", decimal("""
                SELECT COALESCE(SUM(amount), 0) FROM billing_invoices
                WHERE UPPER(status) = 'OVERDUE' OR (due_date < CURRENT_DATE() AND UPPER(status) = 'PENDING')
                """));
        response.put("failedWebhooks", number("SELECT COUNT(*) FROM billing_webhook_queue WHERE UPPER(status) IN ('FAILED', 'DEAD')"));
        response.put("failedNotifications", number("SELECT COUNT(*) FROM billing_notifications WHERE UPPER(status) = 'FAILED'"));
        String filter = text(status);
        response.put("invoices", jdbcTemplate.queryForList("""
                SELECT bi.id, bi.tenant_id AS tenantId, t.name AS clientName, bi.plan_name AS planName,
                       bi.amount, bi.due_date AS dueDate, bi.status, bi.paid_at AS paidAt,
                       bi.checkout_url AS checkoutUrl, bi.created_at AS createdAt
                FROM billing_invoices bi
                JOIN tenants t ON t.id = bi.tenant_id
                WHERE (? = 'all' OR UPPER(bi.status) = UPPER(?))
                ORDER BY bi.created_at DESC
                LIMIT ?
                """, filter.isBlank() ? "all" : filter, filter, safeLimit(limit)));
        return response;
    }

    @Transactional
    public Map<String, Object> updateDeviceBilling(String tenantId, String storeId, String deviceId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        String store = required(storeId, "storeId");
        String device = required(deviceId, "deviceId");
        Map<String, Object> current = jdbcTemplate.queryForList("""
                SELECT store_id AS storeId, status, billable FROM tenant_devices
                WHERE tenant_id = ? AND store_id = ? AND id = ? LIMIT 1
                """, tenant, store, device).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Dispositivo nao encontrado."));
        boolean billable = request == null || !request.containsKey("billable") || boolValue(request.get("billable"));
        String status = defaultValue(value(request, "status"), billable ? "ACTIVE" : "INACTIVE").toUpperCase();
        if (!List.of("ACTIVE", "INACTIVE", "BLOCKED").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status de dispositivo invalido.");
        }
        String reason = required(value(request, "reason"), "reason");
        jdbcTemplate.update("""
                UPDATE tenant_devices
                SET status = ?, billable = ?, activated_at = CASE WHEN ? THEN COALESCE(activated_at, CURRENT_TIMESTAMP) ELSE activated_at END,
                    deactivated_at = CASE WHEN ? THEN NULL ELSE CURRENT_TIMESTAMP END
                WHERE tenant_id = ? AND store_id = ? AND id = ?
                """, status, billable, billable, billable, tenant, store, device);
        String actor = AuthContext.current().map(AuthTokenService.SessionToken::username).orElse("zentrix-admin");
        jdbcTemplate.update("""
                INSERT INTO device_billing_events
                    (tenant_id, store_id, device_id, event_type, previous_status, new_status, reason, actor)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenant, current.get("storeId"), device, billable ? "ACTIVATED" : "DEACTIVATED",
                current.get("status"), status, reason, actor);
        auditService.recordCurrent("ZENTRIX_ADMIN_DEVICE_BILLING_UPDATED", "tenant_devices", device,
                "Faturamento do aplicativo alterado para " + (billable ? "ativo" : "inativo") + ".", "ALERTA", reason);
        panelCacheService.clear();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenantId", tenant);
        result.put("storeId", store);
        result.put("deviceId", device);
        result.put("status", status);
        result.put("billable", billable);
        return result;
    }

    public List<Map<String, Object>> supportNotes(String tenantId) {
        initializer.ensureReady();
        return jdbcTemplate.queryForList("""
                SELECT id, category, status, priority, note, assigned_to AS assignedTo,
                       created_by AS createdBy, created_at AS createdAt, resolved_at AS resolvedAt
                FROM support_notes WHERE tenant_id = ? ORDER BY created_at DESC LIMIT 50
                """, required(tenantId, "tenantId"));
    }

    @Transactional
    public Map<String, Object> addSupportNote(String tenantId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        ensureTenantExists(tenant);
        String note = required(value(request, "note"), "note");
        String category = defaultValue(value(request, "category"), "SUPPORT").toUpperCase();
        String priority = defaultValue(value(request, "priority"), "NORMAL").toUpperCase();
        String actor = AuthContext.current().map(AuthTokenService.SessionToken::username).orElse("zentrix-admin");
        jdbcTemplate.update("""
                INSERT INTO support_notes (tenant_id, category, priority, note, assigned_to, created_by)
                VALUES (?, ?, ?, ?, NULLIF(?, ''), ?)
                """, tenant, category, priority, note, value(request, "assignedTo"), actor);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        auditService.recordCurrent("ZENTRIX_ADMIN_SUPPORT_NOTE_CREATED", "support_notes", String.valueOf(id),
                "Nota interna adicionada ao cliente.", "INFO", note);
        return Map.of("id", id == null ? 0 : id, "tenantId", tenant, "status", "OPEN");
    }

    @Transactional
    public Map<String, Object> resolveSupportNote(String tenantId, long noteId) {
        initializer.ensureReady();
        int updated = jdbcTemplate.update("""
                UPDATE support_notes SET status = 'RESOLVED', resolved_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ?
                """, noteId, required(tenantId, "tenantId"));
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nota nao encontrada.");
        return Map.of("id", noteId, "status", "RESOLVED");
    }

    public Map<String, Object> testClientAccess(String tenantId) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        Map<String, Object> client = client(tenant);
        String tenantStatus = String.valueOf(client.getOrDefault("status", "ACTIVE"));
        if (restrictedStatus(tenantStatus)) {
            return Map.of("allowed", false, "tenantId", tenant, "message", "Cliente bloqueado: " + client.getOrDefault("blockReason", "sem motivo informado."));
        }
        List<Map<String, Object>> licenses = jdbcTemplate.queryForList("""
                SELECT status, expires_at AS expiresAt
                FROM licenses
                WHERE tenant_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, tenant);
        if (licenses.isEmpty()) {
            return Map.of("allowed", true, "tenantId", tenant, "message", "Cliente sem assinatura cadastrada. Acesso permitido por compatibilidade.");
        }
        Map<String, Object> license = licenses.get(0);
        String licenseStatus = String.valueOf(license.get("status"));
        Object expiresAt = license.get("expiresAt");
        if (restrictedStatus(licenseStatus)) {
            return Map.of("allowed", false, "tenantId", tenant, "message", "Assinatura bloqueada: " + licenseStatus + ".");
        }
        if (expiresAt instanceof Timestamp timestamp && timestamp.toLocalDateTime().isBefore(LocalDateTime.now())) {
            return Map.of("allowed", false, "tenantId", tenant, "message", "Assinatura vencida em " + timestamp + ".");
        }
        return Map.of("allowed", true, "tenantId", tenant, "message", "Acesso liberado para o cliente.");
    }

    @Transactional
    public Map<String, Object> updateClientStatus(String tenantId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        String status = required(value(request, "status"), "status").toUpperCase();
        if (!validStatus(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status invalido.");
        }
        boolean restricted = restrictedStatus(status);
        String reason = text(value(request, "reason"));
        if (restricted && reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo do bloqueio.");
        }
        String operator = AuthContext.current()
                .map(AuthTokenService.SessionToken::username)
                .orElse("zentrix-admin");
        int updated = jdbcTemplate.update("""
                UPDATE tenants
                SET status = ?, block_reason = ?, blocked_at = ?, blocked_by = ?
                WHERE id = ?
                """, status, restricted ? reason : null, restricted ? Timestamp.valueOf(LocalDateTime.now()) : null, restricted ? operator : null, tenant);
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente nao encontrado.");
        }
        if (request != null && Boolean.TRUE.equals(request.get("updateLicense"))) {
            jdbcTemplate.update("""
                    UPDATE licenses
                    SET status = ?
                    WHERE tenant_id = ?
                    ORDER BY id DESC
                    LIMIT 1
                    """, status, tenant);
        }
        auditService.recordCurrent("ZENTRIX_ADMIN_CLIENT_STATUS_UPDATED", "tenants", tenant,
                "Status do cliente alterado para " + status + ".", "CRITICO", reason);
        panelCacheService.clear();
        return Map.of(
                "tenantId", tenant,
                "status", status,
                "updated", updated,
                "blockReason", restricted ? reason : ""
        );
    }

    @Transactional
    public Map<String, Object> updateStoreStatus(String tenantId, String storeId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        String store = required(storeId, "storeId");
        String status = required(value(request, "status"), "status").toUpperCase();
        if (!List.of("ACTIVE", "BLOCKED", "SUSPENDED", "INACTIVE").contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Status de loja invalido.");
        }
        boolean restricted = restrictedStatus(status);
        String reason = text(value(request, "reason"));
        if (restricted && reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe o motivo da alteracao da loja.");
        }
        String previousStatus = jdbcTemplate.queryForList("""
                SELECT status
                FROM tenant_stores
                WHERE tenant_id = ? AND id = ?
                LIMIT 1
                """, tenant, store).stream()
                .findFirst()
                .map(row -> String.valueOf(row.getOrDefault("status", "ACTIVE")))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Loja nao encontrada."));
        AuthTokenService.SessionToken session = AuthContext.current().orElse(null);
        String operator = session == null ? "zentrix-admin" : session.username();
        int updated = jdbcTemplate.update("""
                UPDATE tenant_stores
                SET status = ?, block_reason = ?, blocked_at = ?, blocked_by = ?, updated_at = CURRENT_TIMESTAMP
                WHERE tenant_id = ? AND id = ?
                """, status, restricted ? reason : null,
                restricted ? Timestamp.valueOf(LocalDateTime.now()) : null,
                restricted ? operator : null, tenant, store);
        auditService.record(
                tenant,
                store,
                null,
                session == null ? "ZENTRIX_ADMIN" : session.sourceId(),
                session == null ? "zentrix-admin" : session.username(),
                "ZENTRIX_ADMIN_STORE_STATUS_UPDATED",
                "tenant_stores",
                store,
                "Status da loja " + store + " do cliente " + tenant + " alterado para " + status + ".",
                "CRITICO",
                previousStatus,
                status,
                reason,
                "ZENTRIX_ADMIN",
                null,
                session == null ? null : session.role()
        );
        panelCacheService.clear();
        return Map.of(
                "tenantId", tenant,
                "storeId", store,
                "previousStatus", previousStatus,
                "status", status,
                "updated", updated,
                "reason", reason
        );
    }

    @Transactional
    public Map<String, Object> createActivationCode(String tenantId, Map<String, Object> request) {
        initializer.ensureReady();
        String tenant = required(tenantId, "tenantId");
        ensureTenantExists(tenant);
        Map<String, Object> code = provisioningService.createActivationCode(new ActivationCodeRequest(
                tenant,
                value(request, "storeId"),
                null,
                null,
                defaultValue(value(request, "storeName"), "Nova loja"),
                value(request, "sourceId"),
                intValue(request.get("expiresMinutes"), 1440)
        ));
        auditService.recordCurrent("ZENTRIX_ADMIN_ACTIVATION_CODE_CREATED", "activation_codes", String.valueOf(code.get("code")),
                "Codigo de ativacao criado pelo painel administrativo Zentrix.", "ALERTA", value(request, "reason"));
        panelCacheService.clear();
        return code;
    }

    private void ensureTenantExists(String tenantId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tenants WHERE id = ?", Long.class, tenantId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente nao encontrado.");
        }
    }

    private long number(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }

    private long number(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private BigDecimal decimal(String sql) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class);
        return value == null ? BigDecimal.ZERO : value;
    }

    private int safeLimit(int limit) {
        return Math.max(1, Math.min(limit <= 0 ? 100 : limit, 300));
    }

    private String normalizePlan(String plan) {
        String value = text(plan).toUpperCase().replace("Á", "A").replace("É", "E").replace("Í", "I").replace("Ó", "O").replace("Ú", "U");
        if (value.equals("BASICO") || value.equals("BASIC") || value.equals("LEGACY") || value.equals("SEM PLANO")) {
            return "BASICO";
        }
        if (value.equals("INTERMEDIARIO") || value.equals("INTERMEDIATE")) {
            return "INTERMEDIARIO";
        }
        if (value.equals("PRO") || value.equals("PROFISSIONAL")) {
            return "PRO";
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Plano invalido.");
    }

    private PlanDefinition planDefinition(String plan) {
        return PLANS.stream()
                .filter(item -> item.code().equalsIgnoreCase(plan))
                .findFirst()
                .orElse(PLANS.get(0));
    }

    private boolean validStatus(String status) {
        return List.of("ACTIVE", "TRIAL", "BLOCKED", "SUSPENDED", "EXPIRED", "CANCELLED", "CANCELED", "INACTIVE").contains(status);
    }

    private boolean restrictedStatus(String status) {
        String value = text(status).toUpperCase();
        return List.of("BLOCKED", "SUSPENDED", "EXPIRED", "CANCELLED", "CANCELED", "INACTIVE").contains(value);
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> castRows(Object value) {
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    private void addBilling(Map<String, Object> row, String tenantId, String planName) {
        String safePlan = text(planName);
        if (safePlan.isBlank() || "null".equalsIgnoreCase(safePlan)) {
            safePlan = "BASICO";
        }
        Map<String, Object> billing = billingSummary(tenantId, safePlan);
        row.put("billing", billing);
        row.put("monthlyTotal", billing.get("monthlyTotal"));
        row.put("activeStores", billing.get("activeStores"));
        row.put("pdvApps", billing.get("pdvApps"));
        row.put("appGestaoApps", billing.get("appGestaoApps"));
    }

    private String value(Map<String, Object> request, String key) {
        return request == null || request.get(key) == null ? "" : String.valueOf(request.get(key)).trim();
    }

    private String text(String value) {
        return value == null ? "" : value.trim();
    }

    private String required(String value, String field) {
        String text = text(value);
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Informe " + field + ".");
        }
        return text;
    }

    private String defaultValue(String value, String fallback) {
        String text = text(value);
        return text.isBlank() ? fallback : text;
    }

    private int intValue(Object value, int fallback) {
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Math.max(0, Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Numero invalido.");
        }
    }

    private boolean boolValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = String.valueOf(value == null ? "" : value).trim();
        return "true".equalsIgnoreCase(text) || "1".equals(text) || "on".equalsIgnoreCase(text);
    }

    private Timestamp timestampOrNull(String value) {
        String text = text(value);
        if (text.isBlank()) {
            return null;
        }
        try {
            if (text.length() == 10) {
                return Timestamp.valueOf(LocalDateTime.parse(text + "T23:59:59"));
            }
            return Timestamp.valueOf(LocalDateTime.parse(text.replace(' ', 'T')));
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Data invalida.");
        }
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private record PlanDefinition(
            String code,
            String name,
            BigDecimal monthlyStorePrice,
            int includedPdvPerStore,
            int includedAppGestaoPerStore,
            BigDecimal extraPdvPrice,
            BigDecimal extraAppGestaoPrice,
            boolean appGestaoIncluded,
            String description
    ) {
    }
}
