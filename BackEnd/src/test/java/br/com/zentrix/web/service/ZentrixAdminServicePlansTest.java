package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void validatesCpfAndCnpjCheckDigits() {
        assertTrue(ZentrixAdminService.validCpfCnpj("529.982.247-25"));
        assertTrue(ZentrixAdminService.validCpfCnpj("11.222.333/0001-81"));
        assertFalse(ZentrixAdminService.validCpfCnpj("111.111.111-11"));
        assertFalse(ZentrixAdminService.validCpfCnpj("11.222.333/0001-00"));
    }
}
