package br.com.zentrix.web.dto;

public record ReportFilterRequest(
        String period,
        String store,
        String operator,
        String paymentMethod,
        String status
) {
}
