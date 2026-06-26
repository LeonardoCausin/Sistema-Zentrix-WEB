package br.com.zentrix.web.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SettingsService {
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
        response.put("taxaCartaoCredito", decimal(tenantId, storeId, "taxa_cartao_credito", BigDecimal.ZERO));
        response.put("taxaCartaoDebito", decimal(tenantId, storeId, "taxa_cartao_debito", BigDecimal.ZERO));
        response.put("permitirEstoqueNegativo", bool(tenantId, storeId, "permitir_estoque_negativo", false));
        response.put("descontoMaximoPadrao", decimal(tenantId, storeId, "desconto_maximo_padrao", BigDecimal.valueOf(10)));
        response.put("exigirMotivoCancelamento", bool(tenantId, storeId, "exigir_motivo_cancelamento", true));
        return response;
    }

    private String normalizeStore(String storeId) {
        return storeId == null || storeId.isBlank() || "all".equalsIgnoreCase(storeId) ? "all" : storeId;
    }
}
