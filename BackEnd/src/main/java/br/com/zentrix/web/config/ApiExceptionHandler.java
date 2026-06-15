package br.com.zentrix.web.config;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> responseStatus(ResponseStatusException exception) {
        return ResponseEntity
                .status(exception.getStatusCode())
                .body(error(exception.getStatusCode().value(), publicMessage(exception)));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation() {
        return ResponseEntity
                .badRequest()
                .body(error(HttpStatus.BAD_REQUEST.value(), "Confira os dados informados."));
    }

    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, Object>> unavailable() {
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(error(HttpStatus.SERVICE_UNAVAILABLE.value(), "Sistema temporariamente indisponivel."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic() {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "Nao foi possivel concluir a operacao."));
    }

    private String publicMessage(ResponseStatusException exception) {
        if (exception.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            return "Usuario ou senha invalidos.";
        }
        if (exception.getStatusCode().value() == HttpStatus.TOO_MANY_REQUESTS.value()) {
            return "Muitas tentativas. Aguarde alguns minutos e tente novamente.";
        }
        return "Nao foi possivel concluir a operacao.";
    }

    private Map<String, Object> error(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("message", message);
        body.put("timestamp", OffsetDateTime.now().toString());
        return body;
    }
}
