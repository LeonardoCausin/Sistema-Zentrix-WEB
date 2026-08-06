package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ZentrixAdminServicePlansTest {
    @Test
    void chargesFortyNineNinetyForEveryAdditionalPdv() {
        ZentrixAdminService service = new ZentrixAdminService(null, null, null, null, null);

        List<Map<String, Object>> plans = service.plans();

        assertEquals(3, plans.size());
        for (Map<String, Object> plan : plans) {
            assertEquals(new BigDecimal("49.90"), plan.get("extraPdvPrice"));
        }
    }
}
