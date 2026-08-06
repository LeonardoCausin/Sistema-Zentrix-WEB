package br.com.zentrix.web.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BillingService {
    private static final String PROVIDER = "ASAAS";

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final ZentrixAdminService adminService;
    private final AsaasClient asaasClient;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final String webhookToken;
    private final int paymentDueDays;

    public BillingService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            ZentrixAdminService adminService,
            AsaasClient asaasClient,
            AuditService auditService,
            ObjectMapper objectMapper,
            @Value("${zentrix.billing.asaas.webhook-token:}") String webhookToken,
            @Value("${zentrix.billing.asaas.payment-due-days:3}") int paymentDueDays
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.adminService = adminService;
        this.asaasClient = asaasClient;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.webhookToken = webhookToken == null ? "" : webhookToken.trim();
        this.paymentDueDays = Math.max(0, Math.min(paymentDueDays, 30));
    }

    public Map<String, Object> current(String tenantId) {
        initializer.ensureReady();
        return jdbcTemplate.queryForList("""
                SELECT id AS invoiceId, provider_payment_id AS providerPaymentId, plan_name AS planName,
                       amount, coverage_start AS coverageStart, coverage_end AS coverageEnd,
                       due_date AS dueDate, status, checkout_url AS checkoutUrl, paid_at AS paidAt,
                       created_at AS createdAt, updated_at AS updatedAt
                FROM billing_invoices
                WHERE tenant_id = ?
                ORDER BY created_at DESC
                LIMIT 1
                """, tenantId).stream().findFirst().orElse(Map.of("status", "NONE"));
    }

    public Map<String, Object> checkout(String tenantId) {
        initializer.ensureReady();
        asaasClient.requireConfigured();
        String tenant = requiredTenant(tenantId);
        Map<String, Object> profile = tenantProfile(tenant);
        String plan = latestPlan(tenant);
        Map<String, Object> summary = adminService.billingSummary(tenant, plan);
        BigDecimal amount = money(summary.get("monthlyTotal"));
        if (amount.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "O valor da assinatura nao pode ser zero.");
        }

        LocalDate coverageStart = nextCoverageStart(tenant);
        LocalDate coverageEnd = coverageStart.plusMonths(1).minusDays(1);
        LocalDate dueDate = LocalDate.now().plusDays(paymentDueDays);
        String invoiceId = deterministicInvoiceId(tenant, plan, amount, coverageStart, coverageEnd);
        insertInvoice(invoiceId, tenant, plan, amount, summary, coverageStart, coverageEnd, dueDate, true);
        Map<String, Object> invoice = invoice(invoiceId);
        if (terminalRetryStatus(invoice.get("status"))) {
            invoiceId = UUID.randomUUID().toString();
            insertInvoice(invoiceId, tenant, plan, amount, summary, coverageStart, coverageEnd, dueDate, false);
            invoice = invoice(invoiceId);
        }
        String checkoutUrl = text(invoice.get("checkoutUrl"));
        if (!checkoutUrl.isBlank() && pendingStatus(invoice.get("status"))) {
            return checkoutResponse(invoice);
        }

        String customerId = asaasCustomerId(tenant, profile);
        Map<String, Object> payment = asaasClient.findPayment(invoiceId);
        if (payment.isEmpty()) {
            payment = asaasClient.createPayment(
                    customerId,
                    amount,
                    dueDate,
                    "Assinatura Zentrix " + plan + " - " + coverageStart + " a " + coverageEnd,
                    invoiceId
            );
        }
        String paymentId = requiredProviderValue(payment, "id", "O Asaas nao retornou o identificador da cobranca.");
        checkoutUrl = requiredProviderValue(payment, "invoiceUrl", "O Asaas nao retornou o link de pagamento.");
        String providerStatus = normalizedStatus(payment.get("status"));
        jdbcTemplate.update("""
                UPDATE billing_invoices
                SET provider_payment_id = ?, checkout_url = ?, status = ?, due_date = ?
                WHERE id = ?
                """, paymentId, checkoutUrl, providerStatus, Date.valueOf(dueDate), invoiceId);
        auditService.recordCurrent("BILLING_CHECKOUT_CREATED", "billing_invoices", invoiceId,
                "Cobranca mensal criada no Asaas.", "INFO", null);
        return checkoutResponse(invoice(invoiceId));
    }

    @Transactional
    public Map<String, Object> processAsaasWebhook(String receivedToken, Map<String, Object> payload) {
        initializer.ensureReady();
        validateWebhookToken(receivedToken);
        String eventId = text(payload == null ? null : payload.get("id"));
        String eventType = normalizedStatus(payload == null ? null : payload.get("event"));
        if (eventId.isBlank() || eventType.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Webhook Asaas sem identificacao do evento.");
        }
        Object paymentObject = payload.get("payment");
        if (!(paymentObject instanceof Map<?, ?> paymentPayload)) {
            recordIgnoredEvent(eventId, eventType, null);
            return Map.of("received", true, "ignored", true);
        }
        String paymentId = text(paymentPayload.get("id"));
        if (paymentId.isBlank()) {
            recordIgnoredEvent(eventId, eventType, null);
            return Map.of("received", true, "ignored", true);
        }

        Map<String, Object> verifiedPayment = asaasClient.payment(paymentId);
        String verifiedId = text(verifiedPayment.get("id"));
        if (!paymentId.equals(verifiedId)) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "A cobranca nao pode ser confirmada no Asaas.");
        }
        String externalReference = text(verifiedPayment.get("externalReference"));
        List<Map<String, Object>> invoices = jdbcTemplate.queryForList("""
                SELECT id, tenant_id AS tenantId, plan_name AS planName, amount,
                       coverage_start AS coverageStart, coverage_end AS coverageEnd,
                       status, license_id AS licenseId
                FROM billing_invoices
                WHERE (provider = ? AND provider_payment_id = ?) OR external_reference = ?
                LIMIT 1
                """, PROVIDER, paymentId, externalReference);
        if (invoices.isEmpty()) {
            recordIgnoredEvent(eventId, eventType, paymentId);
            return Map.of("received", true, "ignored", true);
        }
        int inserted = jdbcTemplate.update("""
                INSERT IGNORE INTO billing_webhook_events
                    (provider, event_id, event_type, provider_payment_id)
                VALUES (?, ?, ?, ?)
                """, PROVIDER, eventId, eventType, paymentId);
        if (inserted == 0) {
            return Map.of("received", true, "duplicate", true);
        }

        Map<String, Object> invoice = invoices.get(0);
        BigDecimal expectedAmount = money(invoice.get("amount"));
        BigDecimal receivedAmount = money(verifiedPayment.get("value"));
        if (expectedAmount.compareTo(receivedAmount) != 0) {
            jdbcTemplate.update("UPDATE billing_invoices SET status = 'AMOUNT_MISMATCH' WHERE id = ?", invoice.get("id"));
            finishEvent(eventId);
            audit(invoice, "BILLING_AMOUNT_MISMATCH", "Valor confirmado pelo Asaas diverge da fatura Zentrix.", "CRITICO");
            return Map.of("received", true, "processed", false, "reason", "AMOUNT_MISMATCH");
        }

        jdbcTemplate.update("""
                UPDATE billing_invoices
                SET provider_payment_id = ?
                WHERE id = ?
                """, paymentId, invoice.get("id"));
        String providerStatus = normalizedStatus(verifiedPayment.get("status"));
        if (paidStatus(providerStatus, normalizedStatus(verifiedPayment.get("billingType")))) {
            grantLicense(invoice, providerStatus);
        } else if (revokedStatus(providerStatus)) {
            revokeLicense(invoice, providerStatus);
        } else {
            jdbcTemplate.update("UPDATE billing_invoices SET status = ? WHERE id = ?", providerStatus, invoice.get("id"));
        }
        finishEvent(eventId);
        return Map.of("received", true, "processed", true, "status", providerStatus);
    }

    private void grantLicense(Map<String, Object> invoice, String providerStatus) {
        String invoiceId = text(invoice.get("id"));
        String tenantId = text(invoice.get("tenantId"));
        Object licenseId = invoice.get("licenseId");
        if (licenseId == null) {
            Map<String, Object> limits = jdbcTemplate.queryForList("""
                    SELECT max_stores AS maxStores, max_devices AS maxDevices
                    FROM licenses
                    WHERE tenant_id = ?
                    ORDER BY id DESC
                    LIMIT 1
                    """, tenantId).stream().findFirst().orElse(Map.of("maxStores", 1, "maxDevices", 1));
            LocalDate starts = localDate(invoice.get("coverageStart"));
            LocalDate ends = localDate(invoice.get("coverageEnd"));
            jdbcTemplate.update("""
                    INSERT INTO licenses
                        (tenant_id, plan_name, status, starts_at, expires_at, max_stores, max_devices)
                    VALUES (?, ?, 'ACTIVE', ?, ?, ?, ?)
                    """, tenantId, invoice.get("planName"),
                    Timestamp.valueOf(starts.atStartOfDay()), Timestamp.valueOf(ends.atTime(LocalTime.MAX).withNano(0)),
                    integer(limits.get("maxStores"), 1), integer(limits.get("maxDevices"), 1));
            licenseId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        } else {
            jdbcTemplate.update("UPDATE licenses SET status = 'ACTIVE' WHERE id = ?", licenseId);
        }
        jdbcTemplate.update("""
                UPDATE billing_invoices
                SET status = ?, paid_at = COALESCE(paid_at, CURRENT_TIMESTAMP), license_id = ?
                WHERE id = ?
                """, providerStatus, licenseId, invoiceId);
        jdbcTemplate.update("""
                UPDATE tenants
                SET status = 'ACTIVE', block_reason = NULL, blocked_at = NULL, blocked_by = NULL
                WHERE id = ? AND UPPER(status) = 'EXPIRED'
                """, tenantId);
        jdbcTemplate.update("""
                UPDATE tenant_stores
                SET status = 'ACTIVE'
                WHERE tenant_id = ? AND UPPER(status) = 'EXPIRED'
                """, tenantId);
        audit(invoice, "BILLING_PAYMENT_CONFIRMED", "Pagamento confirmado e assinatura renovada.", "INFO");
    }

    private void revokeLicense(Map<String, Object> invoice, String providerStatus) {
        Object licenseId = invoice.get("licenseId");
        if (licenseId != null) {
            jdbcTemplate.update("UPDATE licenses SET status = 'BLOCKED' WHERE id = ?", licenseId);
        }
        jdbcTemplate.update("UPDATE billing_invoices SET status = ? WHERE id = ?", providerStatus, invoice.get("id"));
        audit(invoice, "BILLING_PAYMENT_REVOKED", "Pagamento estornado ou contestado; assinatura bloqueada.", "CRITICO");
    }

    private String asaasCustomerId(String tenantId, Map<String, Object> profile) {
        String saved = jdbcTemplate.queryForList("""
                SELECT provider_customer_id AS providerCustomerId
                FROM billing_customers
                WHERE tenant_id = ? AND provider = ?
                LIMIT 1
                """, tenantId, PROVIDER).stream()
                .findFirst().map(row -> text(row.get("providerCustomerId"))).orElse("");
        if (!saved.isBlank()) {
            return saved;
        }
        Map<String, Object> customer = asaasClient.findCustomer(tenantId);
        if (customer.isEmpty()) {
            String document = text(profile.get("document")).replaceAll("\\D", "");
            if (document.length() != 11 && document.length() != 14) {
                throw new ResponseStatusException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "Cadastre um CPF ou CNPJ valido para esta empresa antes de gerar o pagamento."
                );
            }
            customer = asaasClient.createCustomer(
                    tenantId,
                    text(profile.get("name")),
                    document,
                    text(profile.get("email"))
            );
        }
        String customerId = requiredProviderValue(customer, "id", "O Asaas nao retornou o identificador do cliente.");
        jdbcTemplate.update("""
                INSERT INTO billing_customers (tenant_id, provider, provider_customer_id)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE provider_customer_id = VALUES(provider_customer_id)
                """, tenantId, PROVIDER, customerId);
        return customerId;
    }

    private Map<String, Object> tenantProfile(String tenantId) {
        return jdbcTemplate.queryForList("""
                SELECT t.name, t.document,
                       (SELECT u.username FROM users u
                        WHERE u.tenant_id = t.id AND u.active = TRUE AND u.username LIKE '%@%'
                        ORDER BY CASE WHEN UPPER(u.role) IN ('ADMIN', 'ADMINISTRADOR', 'OWNER', 'DONO') THEN 0 ELSE 1 END,
                                 u.username
                        LIMIT 1) AS email
                FROM tenants t
                WHERE t.id = ?
                LIMIT 1
                """, tenantId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente nao encontrado."));
    }

    private String latestPlan(String tenantId) {
        return jdbcTemplate.queryForList("""
                SELECT plan_name AS planName
                FROM licenses
                WHERE tenant_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, tenantId).stream().findFirst().map(row -> text(row.get("planName"))).filter(value -> !value.isBlank()).orElse("BASICO");
    }

    private LocalDate nextCoverageStart(String tenantId) {
        Object expiresAt = jdbcTemplate.queryForList("""
                SELECT expires_at AS expiresAt
                FROM licenses
                WHERE tenant_id = ?
                ORDER BY id DESC
                LIMIT 1
                """, tenantId).stream().findFirst().map(row -> row.get("expiresAt")).orElse(null);
        LocalDate today = LocalDate.now();
        if (expiresAt instanceof Timestamp timestamp && !timestamp.toLocalDateTime().isBefore(LocalDateTime.now())) {
            return timestamp.toLocalDateTime().toLocalDate().plusDays(1);
        }
        return today;
    }

    private Map<String, Object> invoice(String invoiceId) {
        return jdbcTemplate.queryForList("""
                SELECT id AS invoiceId, tenant_id AS tenantId, provider_payment_id AS providerPaymentId,
                       plan_name AS planName, amount, coverage_start AS coverageStart, coverage_end AS coverageEnd,
                       due_date AS dueDate, status, checkout_url AS checkoutUrl, paid_at AS paidAt
                FROM billing_invoices
                WHERE id = ?
                LIMIT 1
                """, invoiceId).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Nao foi possivel preparar a fatura."));
    }

    private void insertInvoice(
            String invoiceId,
            String tenantId,
            String plan,
            BigDecimal amount,
            Map<String, Object> summary,
            LocalDate coverageStart,
            LocalDate coverageEnd,
            LocalDate dueDate,
            boolean ignoreDuplicate
    ) {
        String insert = ignoreDuplicate ? "INSERT IGNORE" : "INSERT";
        jdbcTemplate.update(insert + """
                 INTO billing_invoices
                    (id, tenant_id, provider, external_reference, plan_name, amount, breakdown_json,
                     coverage_start, coverage_end, due_date, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """, invoiceId, tenantId, PROVIDER, invoiceId, plan, amount, json(summary),
                Date.valueOf(coverageStart), Date.valueOf(coverageEnd), Date.valueOf(dueDate));
    }

    private Map<String, Object> checkoutResponse(Map<String, Object> invoice) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("invoiceId", invoice.get("invoiceId"));
        response.put("checkoutUrl", invoice.get("checkoutUrl"));
        response.put("amount", invoice.get("amount"));
        response.put("dueDate", invoice.get("dueDate"));
        response.put("coverageStart", invoice.get("coverageStart"));
        response.put("coverageEnd", invoice.get("coverageEnd"));
        response.put("status", invoice.get("status"));
        return response;
    }

    private void validateWebhookToken(String receivedToken) {
        if (webhookToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Webhook Asaas nao configurado.");
        }
        byte[] expected = webhookToken.getBytes(StandardCharsets.UTF_8);
        byte[] received = text(receivedToken).getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, received)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Webhook Asaas nao autorizado.");
        }
    }

    private void recordIgnoredEvent(String eventId, String eventType, String paymentId) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO billing_webhook_events
                    (provider, event_id, event_type, provider_payment_id, processed_at)
                VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
                """, PROVIDER, eventId, eventType, paymentId);
    }

    private void finishEvent(String eventId) {
        jdbcTemplate.update("""
                UPDATE billing_webhook_events
                SET processed_at = CURRENT_TIMESTAMP
                WHERE provider = ? AND event_id = ?
                """, PROVIDER, eventId);
    }

    private void audit(Map<String, Object> invoice, String action, String details, String riskLevel) {
        auditService.record(
                text(invoice.get("tenantId")), "WEB", null, "ASAAS", null,
                action, "billing_invoices", text(invoice.get("id")), details,
                riskLevel, null, null, null, "WEBHOOK", null, null
        );
    }

    private boolean paidStatus(String status, String billingType) {
        return status.equals("RECEIVED")
                || status.equals("RECEIVED_IN_CASH")
                || (status.equals("CONFIRMED") && !billingType.equals("PIX"));
    }

    private boolean revokedStatus(String status) {
        return status.equals("REFUNDED")
                || status.equals("PARTIALLY_REFUNDED")
                || status.equals("CHARGEBACK_REQUESTED")
                || status.equals("CHARGEBACK_DISPUTE")
                || status.equals("AWAITING_CHARGEBACK_REVERSAL");
    }

    private boolean pendingStatus(Object status) {
        String value = normalizedStatus(status);
        return value.equals("PENDING") || value.equals("AWAITING_RISK_ANALYSIS") || value.equals("OVERDUE");
    }

    private boolean terminalRetryStatus(Object status) {
        String value = normalizedStatus(status);
        return value.equals("REFUNDED")
                || value.equals("PARTIALLY_REFUNDED")
                || value.equals("DELETED")
                || value.equals("CANCELLED")
                || value.startsWith("CHARGEBACK")
                || value.equals("AMOUNT_MISMATCH");
    }

    private String deterministicInvoiceId(String tenant, String plan, BigDecimal amount, LocalDate start, LocalDate end) {
        String value = String.join("|", tenant, plan, amount.toPlainString(), start.toString(), end.toString());
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Nao foi possivel calcular a fatura.", exception);
        }
    }

    private String requiredTenant(String value) {
        String tenant = text(value);
        if (tenant.isBlank() || tenant.equalsIgnoreCase("legacy")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empresa invalida para cobranca.");
        }
        return tenant;
    }

    private String requiredProviderValue(Map<String, Object> values, String key, String message) {
        String value = text(values.get(key));
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, message);
        }
        return value;
    }

    private BigDecimal money(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal.setScale(2);
        }
        try {
            return new BigDecimal(text(value)).setScale(2);
        } catch (Exception exception) {
            return BigDecimal.ZERO.setScale(2);
        }
    }

    private LocalDate localDate(Object value) {
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        return LocalDate.parse(text(value));
    }

    private int integer(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(text(value));
        } catch (Exception exception) {
            return fallback;
        }
    }

    private String normalizedStatus(Object value) {
        return text(value).toUpperCase(Locale.ROOT);
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
