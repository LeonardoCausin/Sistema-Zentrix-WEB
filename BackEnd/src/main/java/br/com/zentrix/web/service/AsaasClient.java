package br.com.zentrix.web.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AsaasClient {
    private final boolean enabled;
    private final String apiKey;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public AsaasClient(
            @Value("${zentrix.billing.asaas.enabled:false}") boolean enabled,
            @Value("${zentrix.billing.asaas.base-url:https://api-sandbox.asaas.com/v3}") String baseUrl,
            @Value("${zentrix.billing.asaas.api-key:}") String apiKey,
            ObjectMapper objectMapper
    ) {
        this.enabled = enabled;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl == null ? "" : baseUrl.trim())
                .defaultHeader("User-Agent", "Zentrix-AppGestao/1.0")
                .build();
    }

    public void requireConfigured() {
        if (!isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "O pagamento online ainda nao esta habilitado. Configure o Asaas no servidor."
            );
        }
    }

    public boolean isConfigured() {
        return enabled && !apiKey.isBlank();
    }

    public Map<String, Object> findCustomer(String externalReference) {
        requireConfigured();
        Map<String, Object> response = get(uriBuilder -> uriBuilder
                .path("/customers")
                .queryParam("externalReference", externalReference)
                .queryParam("limit", 1)
                .build());
        Object data = response.get("data");
        if (data instanceof List<?> rows && !rows.isEmpty() && rows.get(0) instanceof Map<?, ?> row) {
            return stringMap(row);
        }
        return Map.of();
    }

    public Map<String, Object> createCustomer(String tenantId, String name, String cpfCnpj, String email) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("cpfCnpj", cpfCnpj);
        body.put("externalReference", tenantId);
        if (email != null && !email.isBlank()) {
            body.put("email", email.trim());
        }
        return post("/customers", body);
    }

    public Map<String, Object> createPayment(
            String customerId,
            BigDecimal amount,
            LocalDate dueDate,
            String description,
            String externalReference
    ) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("customer", customerId);
        body.put("billingType", "UNDEFINED");
        body.put("value", amount);
        body.put("dueDate", dueDate.toString());
        body.put("description", description);
        body.put("externalReference", externalReference);
        return post("/payments", body);
    }

    public Map<String, Object> payment(String paymentId) {
        requireConfigured();
        return get(uriBuilder -> uriBuilder.path("/payments/{id}").build(paymentId));
    }

    public Map<String, Object> findPayment(String externalReference) {
        requireConfigured();
        Map<String, Object> response = get(uriBuilder -> uriBuilder
                .path("/payments")
                .queryParam("externalReference", externalReference)
                .queryParam("limit", 1)
                .build());
        Object data = response.get("data");
        if (data instanceof List<?> rows && !rows.isEmpty() && rows.get(0) instanceof Map<?, ?> row) {
            return stringMap(row);
        }
        return Map.of();
    }

    private Map<String, Object> post(String path, Map<String, Object> body) {
        requireConfigured();
        try {
            Map<?, ?> response = restClient.post()
                    .uri(path)
                    .header("access_token", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
            return response == null ? Map.of() : stringMap(response);
        } catch (RestClientResponseException exception) {
            throw gatewayError(exception);
        } catch (ResourceAccessException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Nao foi possivel conectar ao Asaas agora.", exception);
        }
    }

    private Map<String, Object> get(java.util.function.Function<org.springframework.web.util.UriBuilder, java.net.URI> uriFunction) {
        try {
            Map<?, ?> response = restClient.get()
                    .uri(uriFunction)
                    .header("access_token", apiKey)
                    .retrieve()
                    .body(Map.class);
            return response == null ? Map.of() : stringMap(response);
        } catch (RestClientResponseException exception) {
            throw gatewayError(exception);
        } catch (ResourceAccessException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Nao foi possivel conectar ao Asaas agora.", exception);
        }
    }

    private ResponseStatusException gatewayError(RestClientResponseException exception) {
        String detail = firstErrorDescription(exception.getResponseBodyAsString());
        String message = detail.isBlank()
                ? "O Asaas recusou a solicitacao de pagamento. Confira a configuracao e tente novamente."
                : "O Asaas recusou a solicitacao: " + detail;
        return new ResponseStatusException(HttpStatus.BAD_GATEWAY, message, exception);
    }

    private String firstErrorDescription(String responseBody) {
        try {
            Map<?, ?> body = objectMapper.readValue(responseBody, Map.class);
            Object errors = body.get("errors");
            if (errors instanceof List<?> rows && !rows.isEmpty() && rows.get(0) instanceof Map<?, ?> error) {
                Object description = error.get("description");
                return description == null ? "" : String.valueOf(description).trim();
            }
        } catch (Exception ignored) {
            // A resposta do provedor pode nao estar em JSON.
        }
        return "";
    }

    private Map<String, Object> stringMap(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }
}
