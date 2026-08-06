package br.com.zentrix.web.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class BillingNotificationService {
    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;
    private final JavaMailSender mailSender;
    private final boolean mailEnabled;
    private final String mailFrom;
    private final String webhookUrl;
    private final RestClient restClient = RestClient.create();

    public BillingNotificationService(
            JdbcTemplate jdbcTemplate,
            WebDatabaseInitializer initializer,
            JavaMailSender mailSender,
            @Value("${zentrix.billing.mail-enabled:false}") boolean mailEnabled,
            @Value("${ZENTRIX_BILLING_MAIL_FROM:}") String mailFrom,
            @Value("${zentrix.billing.notification-webhook-url:}") String webhookUrl
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
        this.mailSender = mailSender;
        this.mailEnabled = mailEnabled;
        this.mailFrom = mailFrom == null ? "" : mailFrom.trim();
        this.webhookUrl = webhookUrl == null ? "" : webhookUrl.trim();
    }

    public void scheduleExpirationNotifications() {
        initializer.ensureReady();
        List<Map<String, Object>> subscriptions = jdbcTemplate.queryForList("""
                SELECT l.id AS licenseId, l.tenant_id AS tenantId, t.name, l.plan_name AS planName,
                       DATE(l.expires_at) AS expiresAt,
                       DATEDIFF(DATE(l.expires_at), CURRENT_DATE()) AS daysLeft,
                       COALESCE(bs.grace_days, 3) AS graceDays,
                       COALESCE(t.billing_email, (
                           SELECT u.username FROM users u
                           WHERE u.tenant_id = t.id AND u.active = TRUE AND u.username LIKE '%@%'
                           ORDER BY u.username LIMIT 1
                       )) AS recipient,
                       COALESCE(bs.notification_email, TRUE) AS notificationEmail,
                       COALESCE(bs.notification_webhook, FALSE) AS notificationWebhook
                FROM licenses l
                JOIN (SELECT tenant_id, MAX(id) AS id FROM licenses GROUP BY tenant_id) latest ON latest.id = l.id
                JOIN tenants t ON t.id = l.tenant_id
                LEFT JOIN billing_settings bs ON bs.tenant_id = l.tenant_id
                WHERE UPPER(l.status) IN ('ACTIVE', 'TRIAL')
                  AND l.expires_at IS NOT NULL
                  AND DATEDIFF(DATE(l.expires_at), CURRENT_DATE()) BETWEEN -30 AND 7
                """);
        for (Map<String, Object> subscription : subscriptions) {
            int daysLeft = number(subscription.get("daysLeft"), 999);
            int graceDays = number(subscription.get("graceDays"), 3);
            if (daysLeft != 7 && daysLeft != 3 && daysLeft != 1 && daysLeft != 0 && daysLeft != -graceDays) {
                continue;
            }
            String type = daysLeft > 0 ? "EXPIRY_" + daysLeft
                    : daysLeft == 0 ? "EXPIRY_TODAY" : "GRACE_END";
            String tenantId = text(subscription.get("tenantId"));
            String reference = "license-" + subscription.get("licenseId") + "-" + subscription.get("expiresAt");
            String subject = daysLeft > 0
                    ? "Sua assinatura Zentrix vence em " + daysLeft + " dia(s)"
                    : daysLeft == 0 ? "Sua assinatura Zentrix vence hoje" : "Periodo de tolerancia encerrado";
            String message = message(subscription, daysLeft, graceDays);
            insert(tenantId, reference, "IN_APP", type, null, subject, message, "AVAILABLE");
            String recipient = text(subscription.get("recipient"));
            if (mailEnabled && truthy(subscription.get("notificationEmail")) && !recipient.isBlank()) {
                insert(tenantId, reference, "EMAIL", type, recipient, subject, message, "PENDING");
            }
            if (!webhookUrl.isBlank() && truthy(subscription.get("notificationWebhook"))) {
                insert(tenantId, reference, "WEBHOOK", type, webhookUrl, subject, message, "PENDING");
            }
        }
    }

    public void deliverPending() {
        initializer.ensureReady();
        List<Map<String, Object>> pending = jdbcTemplate.queryForList("""
                SELECT id, tenant_id AS tenantId, channel, recipient, subject, message, notification_type AS type
                FROM billing_notifications
                WHERE status IN ('PENDING', 'RETRY') AND scheduled_for <= CURRENT_TIMESTAMP
                ORDER BY id
                LIMIT 50
                """);
        for (Map<String, Object> notification : pending) {
            long id = ((Number) notification.get("id")).longValue();
            try {
                String channel = text(notification.get("channel"));
                if ("EMAIL".equals(channel)) {
                    sendEmail(notification);
                } else if ("WEBHOOK".equals(channel)) {
                    sendWebhook(notification);
                }
                jdbcTemplate.update("""
                        UPDATE billing_notifications
                        SET status = 'SENT', sent_at = CURRENT_TIMESTAMP, last_error = NULL
                        WHERE id = ?
                        """, id);
            } catch (Exception exception) {
                int attempts = jdbcTemplate.queryForObject(
                        "SELECT attempts FROM billing_notifications WHERE id = ?", Integer.class, id) + 1;
                jdbcTemplate.update("""
                        UPDATE billing_notifications
                        SET status = ?, attempts = ?, last_error = ?, scheduled_for = DATE_ADD(NOW(), INTERVAL 30 MINUTE)
                        WHERE id = ?
                        """, attempts >= 5 ? "FAILED" : "RETRY", attempts, safeError(exception), id);
            }
        }
    }

    public List<Map<String, Object>> notifications(String tenantId, int limit) {
        initializer.ensureReady();
        return jdbcTemplate.queryForList("""
                SELECT id, channel, notification_type AS type, subject, message, status,
                       scheduled_for AS scheduledFor, sent_at AS sentAt, read_at AS readAt, created_at AS createdAt
                FROM billing_notifications
                WHERE tenant_id = ? AND channel = 'IN_APP'
                ORDER BY created_at DESC
                LIMIT ?
                """, tenantId, Math.max(1, Math.min(limit, 100)));
    }

    public void markRead(String tenantId, long id) {
        initializer.ensureReady();
        jdbcTemplate.update("""
                UPDATE billing_notifications
                SET status = 'READ', read_at = CURRENT_TIMESTAMP
                WHERE id = ? AND tenant_id = ? AND channel = 'IN_APP'
                """, id, tenantId);
    }

    private void insert(String tenantId, String reference, String channel, String type, String recipient,
                        String subject, String message, String status) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO billing_notifications
                    (tenant_id, reference_key, channel, notification_type, recipient, subject, message, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, tenantId, reference, channel, type, recipient, subject, message, status);
    }

    private void sendEmail(Map<String, Object> notification) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (!mailFrom.isBlank()) {
            message.setFrom(mailFrom);
        }
        message.setTo(text(notification.get("recipient")));
        message.setSubject(text(notification.get("subject")));
        message.setText(text(notification.get("message")));
        mailSender.send(message);
    }

    private void sendWebhook(Map<String, Object> notification) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", notification.get("tenantId"));
        payload.put("type", notification.get("type"));
        payload.put("subject", notification.get("subject"));
        payload.put("message", notification.get("message"));
        restClient.post().uri(webhookUrl).body(payload).retrieve().toBodilessEntity();
    }

    private String message(Map<String, Object> subscription, int daysLeft, int graceDays) {
        String company = text(subscription.get("name"));
        String expiration = text(subscription.get("expiresAt"));
        if (daysLeft > 0) {
            return company + ", sua assinatura Zentrix vence em " + daysLeft + " dia(s), em " + expiration
                    + ". Acesse o AppGestao para consultar o valor e realizar o pagamento.";
        }
        if (daysLeft == 0) {
            return company + ", sua assinatura Zentrix vence hoje. O periodo de tolerancia e de " + graceDays
                    + " dia(s). Regularize o pagamento para evitar o bloqueio.";
        }
        return company + ", o periodo de tolerancia da assinatura terminou. Realize o pagamento para restaurar o acesso.";
    }

    private boolean truthy(Object value) {
        return value instanceof Boolean bool ? bool : "1".equals(text(value)) || "true".equalsIgnoreCase(text(value));
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private String safeError(Exception exception) {
        String value = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        return value.length() > 480 ? value.substring(0, 480) : value;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
