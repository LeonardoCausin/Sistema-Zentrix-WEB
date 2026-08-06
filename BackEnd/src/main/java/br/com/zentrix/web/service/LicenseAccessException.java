package br.com.zentrix.web.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

public class LicenseAccessException extends ResponseStatusException {
    private final String reasonCode;

    public LicenseAccessException(String reasonCode, String message) {
        super(HttpStatus.PAYMENT_REQUIRED, message);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
