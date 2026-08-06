package br.com.zentrix.web.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AsaasClientTest {
    @Test
    void createsUndefinedPaymentWithApiKeyAndInternalReference() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<String> accessToken = new AtomicReference<>();
        AtomicReference<Map<?, ?>> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v3/payments", exchange -> {
            accessToken.set(exchange.getRequestHeaders().getFirst("access_token"));
            requestBody.set(objectMapper.readValue(exchange.getRequestBody(), Map.class));
            json(exchange, "{\"id\":\"pay_1\",\"invoiceUrl\":\"https://sandbox.asaas.com/i/1\",\"status\":\"PENDING\"}");
        });
        server.start();
        try {
            AsaasClient client = new AsaasClient(
                    true,
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v3",
                    "$aact_hmlg_test",
                    objectMapper
            );

            Map<String, Object> payment = client.createPayment(
                    "cus_1",
                    new BigDecimal("149.80"),
                    LocalDate.of(2026, 8, 9),
                    "Assinatura Zentrix",
                    "invoice-1"
            );

            assertEquals("$aact_hmlg_test", accessToken.get());
            assertEquals("UNDEFINED", requestBody.get().get("billingType"));
            assertEquals("invoice-1", requestBody.get().get("externalReference"));
            assertEquals("pay_1", payment.get("id"));
        } finally {
            server.stop(0);
        }
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
