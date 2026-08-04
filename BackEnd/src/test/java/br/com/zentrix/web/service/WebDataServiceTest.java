package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebDataServiceTest {

    @Test
    void storesDeduplicateLegacyRowsBySourceId() {
        Map<String, Object> officialStore = store("71320070-b210-4848-860c-38df600f996e", "LC Multimarcas");
        Map<String, Object> legacyStore = store("LC Multimarcas", "LC Multimarcas");

        List<Map<String, Object>> stores = WebDataService.deduplicateStores(List.of(officialStore, legacyStore));

        assertEquals(1, stores.size());
        assertEquals("71320070-b210-4848-860c-38df600f996e", stores.get(0).get("id"));
    }

    @Test
    void calculatesExpectedCashBalanceWhenPdvDoesNotSendIt() {
        BigDecimal expected = WebDataService.calculateExpectedBalance(
                new BigDecimal("200.00"),
                null,
                new BigDecimal("150.50"),
                new BigDecimal("30.00"),
                new BigDecimal("20.00")
        );

        assertEquals(new BigDecimal("360.50"), expected);
    }

    @Test
    void keepsExpectedCashBalanceSentByPdv() {
        BigDecimal expected = WebDataService.calculateExpectedBalance(
                new BigDecimal("200.00"),
                new BigDecimal("410.00"),
                new BigDecimal("150.50"),
                new BigDecimal("30.00"),
                new BigDecimal("20.00")
        );

        assertEquals(new BigDecimal("410.00"), expected);
    }

    private Map<String, Object> store(String id, String sourceId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("name", "Lc Multimarcas");
        row.put("label", sourceId);
        row.put("sourceId", sourceId);
        row.put("lastSync", null);
        row.put("totalRows", 0L);
        row.put("isAll", false);
        return row;
    }
}
