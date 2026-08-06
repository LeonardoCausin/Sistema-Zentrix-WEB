package br.com.zentrix.web.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class BillingAutomationService {
    private static final Logger log = LoggerFactory.getLogger(BillingAutomationService.class);

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final BillingService billingService;
    private final AsaasClient asaasClient;
    private final ObjectMapper objectMapper;
    private final BillingNotificationService notificationService;
    private final boolean reconciliationEnabled;
    private final boolean notificationEnabled;
    private final int defaultGraceDays;

    public BillingAutomationService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            BillingService billingService,
            AsaasClient asaasClient,
            ObjectMapper objectMapper,
            BillingNotificationService notificationService,
            @Value("${zentrix.billing.reconciliation-enabled:true}") boolean reconciliationEnabled,
            @Value("${zentrix.billing.notification-enabled:true}") boolean notificationEnabled,
            @Value("${zentrix.billing.grace-days:3}") int defaultGraceDays
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.billingService = billingService;
        this.asaasClient = asaasClient;
        this.objectMapper = objectMapper;
        this.notificationService = notificationService;
        this.reconciliationEnabled = reconciliationEnabled;
        this.notificationEnabled = notificationEnabled;
        this.defaultGraceDays = Math.max(0, Math.min(defaultGraceDays, 30));
    }

    @Scheduled(fixedDelayString = "${ZENTRIX_BILLING_WEBHOOK_WORKER_DELAY_MS:5000}")
    public void processWebhookQueue() {
        initializer.ensureReady();
        List<Map<String, Object>> jobs = jdbcTemplate.queryForList("""
                SELECT id, payload_json AS payloadJson, attempts
                FROM billing_webhook_queue
                WHERE status IN ('PENDING', 'RETRY')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP)
                ORDER BY id
                LIMIT 20
                """);
        for (Map<String, Object> job : jobs) {
            long id = ((Number) job.get("id")).longValue();
            int claimed = jdbcTemplate.update("""
                    UPDATE billing_webhook_queue
                    SET status = 'PROCESSING'
                    WHERE id = ? AND status IN ('PENDING', 'RETRY')
                    """, id);
            if (claimed == 0) {
                continue;
            }
            try {
                Map<String, Object> payload = objectMapper.readValue(
                        String.valueOf(job.get("payloadJson")),
                        new TypeReference<>() { }
                );
                billingService.processQueuedWebhook(payload);
                jdbcTemplate.update("""
                        UPDATE billing_webhook_queue
                        SET status = 'DONE', processed_at = CURRENT_TIMESTAMP, last_error = NULL
                        WHERE id = ?
                        """, id);
            } catch (Exception exception) {
                int attempts = ((Number) job.getOrDefault("attempts", 0)).intValue() + 1;
                String status = attempts >= 8 ? "DEAD" : "RETRY";
                LocalDateTime nextAttempt = LocalDateTime.now().plusMinutes(Math.min(30, attempts * attempts));
                jdbcTemplate.update("""
                        UPDATE billing_webhook_queue
                        SET status = ?, attempts = ?, next_attempt_at = ?, last_error = ?
                        WHERE id = ?
                        """, status, attempts, Timestamp.valueOf(nextAttempt), safeError(exception), id);
                log.warn("Falha no webhook financeiro id={} tentativa={}: {}", id, attempts, safeError(exception));
            }
        }
    }

    @Scheduled(fixedDelayString = "${ZENTRIX_BILLING_RECONCILIATION_DELAY_MS:600000}", initialDelayString = "${ZENTRIX_BILLING_RECONCILIATION_INITIAL_DELAY_MS:30000}")
    public void reconcilePendingInvoices() {
        if (!reconciliationEnabled || !asaasClient.isConfigured()) {
            return;
        }
        initializer.ensureReady();
        List<Map<String, Object>> invoices = jdbcTemplate.queryForList("""
                SELECT provider_payment_id AS paymentId
                FROM billing_invoices
                WHERE provider = 'ASAAS'
                  AND provider_payment_id IS NOT NULL
                  AND UPPER(status) IN ('PENDING', 'OVERDUE', 'CONFIRMED', 'AWAITING_RISK_ANALYSIS')
                ORDER BY updated_at
                LIMIT 100
                """);
        long bucket = System.currentTimeMillis() / 600000L;
        for (Map<String, Object> invoice : invoices) {
            String paymentId = String.valueOf(invoice.get("paymentId"));
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("id", "reconcile-" + paymentId + "-" + bucket);
                payload.put("event", "PAYMENT_RECONCILIATION");
                payload.put("payment", Map.of("id", paymentId));
                billingService.processQueuedWebhook(payload);
            } catch (Exception exception) {
                log.warn("Falha ao reconciliar cobranca {}: {}", paymentId, safeError(exception));
            }
        }
    }

    @Scheduled(cron = "${ZENTRIX_BILLING_MAINTENANCE_CRON:0 15 * * * *}")
    public void maintainSubscriptions() {
        initializer.ensureReady();
        createDefaultSettings();
        if (notificationEnabled) {
            notificationService.scheduleExpirationNotifications();
            notificationService.deliverPending();
        }
        expireLicensesAfterGrace();
    }

    private void createDefaultSettings() {
        jdbcTemplate.update("""
                INSERT IGNORE INTO billing_settings (tenant_id, grace_days)
                SELECT id, ? FROM tenants
                """, defaultGraceDays);
    }

    private void expireLicensesAfterGrace() {
        List<Map<String, Object>> expired = jdbcTemplate.queryForList("""
                SELECT l.id, l.tenant_id AS tenantId
                FROM licenses l
                JOIN (SELECT tenant_id, MAX(id) AS id FROM licenses GROUP BY tenant_id) latest ON latest.id = l.id
                LEFT JOIN billing_settings bs ON bs.tenant_id = l.tenant_id
                WHERE UPPER(l.status) IN ('ACTIVE', 'TRIAL')
                  AND l.expires_at IS NOT NULL
                  AND TIMESTAMPADD(DAY, COALESCE(bs.grace_days, ?), l.expires_at) < CURRENT_TIMESTAMP
                  AND COALESCE(bs.auto_block, TRUE) = TRUE
                """, defaultGraceDays);
        for (Map<String, Object> row : expired) {
            jdbcTemplate.update("UPDATE licenses SET status = 'EXPIRED' WHERE id = ?", row.get("id"));
            jdbcTemplate.update("""
                    UPDATE tenants
                    SET status = 'EXPIRED', block_reason = 'Pagamento da assinatura vencido.'
                    WHERE id = ? AND UPPER(status) = 'ACTIVE'
                    """, row.get("tenantId"));
        }
    }

    private String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            message = exception.getClass().getSimpleName();
        }
        return message.length() > 480 ? message.substring(0, 480) : message;
    }
}
