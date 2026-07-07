package br.com.zentrix.web.service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "dashboard_periodo_padrao",
            "loja_padrao",
            "pagina_inicial",
            "tema_padrao",
            "sessao_expira_minutos",
            "senha_forte_obrigatoria",
            "bloqueio_tentativas_login",
            "permitir_estoque_negativo",
            "exigir_motivo_cancelamento",
            "exigir_motivo_desconto",
            "desconto_maximo_padrao",
            "alerta_estoque_baixo",
            "alerta_pdv_offline",
            "alerta_sync_falha",
            "alerta_caixa_divergente",
            "alerta_backup_atrasado",
            "backup_horario",
            "backup_retencao",
            "backup_alertar_dias",
            "sync_intervalo_segundos",
            "ambiente_nome",
            "pdv_integration_token",
            "maintenance_cache_version"
    );

    private final JdbcTemplate jdbcTemplate;
    private final WebDatabaseInitializer initializer;

    public SettingsService(JdbcTemplate jdbcTemplate, WebDatabaseInitializer initializer) {
        this.jdbcTemplate = jdbcTemplate;
        this.initializer = initializer;
    }

    public String get(String tenantId, String storeId, String key, String fallback) {
        initializer.ensureReady();
        List<String> rows = jdbcTemplate.query("""
                SELECT setting_value
                FROM app_settings
                WHERE tenant_id = ? AND (store_id = ? OR store_id = 'all') AND setting_key = ?
                ORDER BY store_id = ? DESC
                LIMIT 1
                """, (rs, rowNum) -> rs.getString(1), tenantId, normalizeStore(storeId), key, normalizeStore(storeId));
        return rows.isEmpty() || rows.get(0) == null ? fallback : rows.get(0);
    }

    public boolean bool(String tenantId, String storeId, String key, boolean fallback) {
        String value = get(tenantId, storeId, key, String.valueOf(fallback));
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "sim".equalsIgnoreCase(value);
    }

    public BigDecimal decimal(String tenantId, String storeId, String key, BigDecimal fallback) {
        try {
            return new BigDecimal(get(tenantId, storeId, key, fallback.toPlainString()));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Map<String, Object> publicSettings(String tenantId, String storeId) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dashboardPeriodoPadrao", get(tenantId, storeId, "dashboard_periodo_padrao", "today"));
        response.put("lojaPadrao", get(tenantId, storeId, "loja_padrao", "all"));
        response.put("paginaInicial", get(tenantId, storeId, "pagina_inicial", "dashboard.html"));
        response.put("temaPadrao", get(tenantId, storeId, "tema_padrao", "system"));
        response.put("sessaoExpiraMinutos", decimal(tenantId, storeId, "sessao_expira_minutos", BigDecimal.valueOf(480)));
        response.put("senhaForteObrigatoria", bool(tenantId, storeId, "senha_forte_obrigatoria", true));
        response.put("bloqueioTentativasLogin", decimal(tenantId, storeId, "bloqueio_tentativas_login", BigDecimal.valueOf(5)));
        response.put("taxaCartaoCredito", decimal(tenantId, storeId, "taxa_cartao_credito", BigDecimal.ZERO));
        response.put("taxaCartaoDebito", decimal(tenantId, storeId, "taxa_cartao_debito", BigDecimal.ZERO));
        response.put("permitirEstoqueNegativo", bool(tenantId, storeId, "permitir_estoque_negativo", false));
        response.put("descontoMaximoPadrao", decimal(tenantId, storeId, "desconto_maximo_padrao", BigDecimal.valueOf(10)));
        response.put("exigirMotivoCancelamento", bool(tenantId, storeId, "exigir_motivo_cancelamento", true));
        response.put("exigirMotivoDesconto", bool(tenantId, storeId, "exigir_motivo_desconto", true));
        response.put("alertaEstoqueBaixo", bool(tenantId, storeId, "alerta_estoque_baixo", true));
        response.put("alertaPdvOffline", bool(tenantId, storeId, "alerta_pdv_offline", true));
        response.put("alertaSyncFalha", bool(tenantId, storeId, "alerta_sync_falha", true));
        response.put("alertaCaixaDivergente", bool(tenantId, storeId, "alerta_caixa_divergente", true));
        response.put("alertaBackupAtrasado", bool(tenantId, storeId, "alerta_backup_atrasado", true));
        response.put("backupHorario", get(tenantId, storeId, "backup_horario", "23:30"));
        response.put("backupRetencao", decimal(tenantId, storeId, "backup_retencao", BigDecimal.valueOf(14)));
        response.put("backupAlertarDias", decimal(tenantId, storeId, "backup_alertar_dias", BigDecimal.ONE));
        response.put("syncIntervaloSegundos", decimal(tenantId, storeId, "sync_intervalo_segundos", BigDecimal.valueOf(30)));
        response.put("ambienteNome", get(tenantId, storeId, "ambiente_nome", "Produção"));
        response.put("pdvIntegrationTokenConfigured", !get(tenantId, storeId, "pdv_integration_token", "").isBlank());
        response.put("maintenanceCacheVersion", get(tenantId, storeId, "maintenance_cache_version", ""));
        return response;
    }

    @Transactional
    public Map<String, Object> updateSettings(String tenantId, String storeId, Map<String, Object> values) {
        initializer.ensureReady();
        String normalizedStore = normalizeStore(storeId);
        Map<String, String> sanitized = sanitize(values);
        sanitized.forEach((key, value) -> jdbcTemplate.update("""
                INSERT INTO app_settings (tenant_id, store_id, setting_key, setting_value)
                VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE setting_value = VALUES(setting_value), updated_at = CURRENT_TIMESTAMP
                """, tenantId, normalizedStore, key, value));
        return publicSettings(tenantId, normalizedStore);
    }

    private Map<String, String> sanitize(Map<String, Object> values) {
        Map<String, String> response = new HashMap<>();
        if (values == null) {
            return response;
        }
        values.forEach((key, value) -> {
            if (!ALLOWED_KEYS.contains(key)) {
                return;
            }
            String text = value == null ? "" : String.valueOf(value).trim();
            if (key.endsWith("_obrigatoria") || key.startsWith("alerta_") || key.startsWith("exigir_") || key.equals("permitir_estoque_negativo")) {
                text = boolText(value);
            }
            if (key.equals("dashboard_periodo_padrao") && !Set.of("today", "7d", "month", "year").contains(text)) {
                text = "today";
            }
            if (key.equals("tema_padrao") && !Set.of("system", "light", "dark").contains(text)) {
                text = "system";
            }
            if (key.equals("pagina_inicial") && !text.endsWith(".html")) {
                text = "dashboard.html";
            }
            response.put(key, text.length() > 500 ? text.substring(0, 500) : text);
        });
        return response;
    }

    private String boolText(Object value) {
        return Boolean.TRUE.equals(value) || "true".equalsIgnoreCase(String.valueOf(value)) || "on".equalsIgnoreCase(String.valueOf(value)) ? "true" : "false";
    }

    private String normalizeStore(String storeId) {
        return storeId == null || storeId.isBlank() || "all".equalsIgnoreCase(storeId) ? "all" : storeId;
    }
}
