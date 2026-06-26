package br.com.zentrix.web.service;

import java.util.Optional;

public final class AuthContext {
    private static final ThreadLocal<AuthTokenService.SessionToken> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthTokenService.SessionToken session) {
        CURRENT.set(session);
    }

    public static Optional<AuthTokenService.SessionToken> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    public static String tenantId() {
        return current()
                .map(AuthTokenService.SessionToken::tenantId)
                .orElse("legacy");
    }

    public static void clear() {
        CURRENT.remove();
    }
}
