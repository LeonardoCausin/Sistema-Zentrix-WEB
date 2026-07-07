package br.com.zentrix.web.config;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException exception, HttpServletRequest request) {
        return ResponseEntity
                .status(exception.getStatusCode())
                .body(error(exception.getStatusCode().value(), HttpStatus.valueOf(exception.getStatusCode().value()).getReasonPhrase(), publicMessage(exception), request));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, Object> body = error(HttpStatus.BAD_REQUEST.value(), HttpStatus.BAD_REQUEST.getReasonPhrase(), "Confira os dados informados.", request);
        body.put("fields", exception.getBindingResult().getFieldErrors().stream()
                .map(field -> Map.of("field", field.getField(), "message", field.getDefaultMessage() == null ? "inválido" : field.getDefaultMessage()))
                .toList());
        return ResponseEntity
                .badRequest()
                .body(body);
    }

    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> unavailable(HttpServletRequest request) {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(HttpStatus.SERVICE_UNAVAILABLE.value(), HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), "O sistema não conseguiu acessar os dados agora. Tente novamente em instantes.", request));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception exception, HttpServletRequest request) {
        log.error("Erro inesperado na API Zentrix AppGestão", exception);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(HttpStatus.INTERNAL_SERVER_ERROR.value(), HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(), "Não conseguimos concluir sua solicitação agora. Tente novamente em instantes.", request));
    }

    private String publicMessage(ResponseStatusException exception) {
        if (exception.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            return "Usuário ou senha inválidos.";
        }
        if (exception.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return "Muitas tentativas. Aguarde alguns minutos e tente novamente.";
        }
        if (exception.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
            return exception.getReason() == null ? "Você não tem permissão para fazer isso." : exception.getReason();
        }
        if (exception.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()
                || exception.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) {
            return exception.getReason() == null ? "Confira os dados informados." : exception.getReason();
        }
        return "Não conseguimos concluir sua solicitação agora. Tente novamente em instantes.";
    }

    private Map<String, Object> error(int status, String error, String message, HttpServletRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", OffsetDateTime.now().toString());
        body.put("status", status);
        body.put("error", error);
        body.put("message", message);
        body.put("path", request == null ? null : request.getRequestURI());
        return body;
    }
}
