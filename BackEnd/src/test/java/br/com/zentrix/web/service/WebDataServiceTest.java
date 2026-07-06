package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
