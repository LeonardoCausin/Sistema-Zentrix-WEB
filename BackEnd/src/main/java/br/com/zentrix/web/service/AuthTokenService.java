package br.com.zentrix.web.service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {
    private static final Duration TOKEN_TTL = Duration.ofHours(8);

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, SessionToken> tokens = new ConcurrentHashMap<>();

    public String issue(String username, String displayName, String role) {
        purgeExpired();
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tokens.put(token, new SessionToken(username, displayName, role, Instant.now().plus(TOKEN_TTL)));
        return token;
    }

    public Optional<SessionToken> validate(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        SessionToken session = tokens.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            tokens.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
    }

    public record SessionToken(String username, String displayName, String role, Instant expiresAt) {
    }
}
