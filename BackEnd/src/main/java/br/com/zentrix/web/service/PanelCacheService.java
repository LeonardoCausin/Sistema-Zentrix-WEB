package br.com.zentrix.web.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

@Service
public class PanelCacheService {
    private static final int MAX_ENTRIES = 512;

    private final ConcurrentHashMap<String, CacheEntry> entries = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    public <T> T get(String key, Duration ttl, Supplier<T> loader) {
        Instant now = Instant.now();
        CacheEntry cached = entries.get(key);
        if (cached != null && now.isBefore(cached.expiresAt())) {
            return (T) cached.value();
        }
        T value = loader.get();
        if (value != null && !ttl.isNegative() && !ttl.isZero()) {
            prune(now);
            entries.put(key, new CacheEntry(value, now.plus(ttl)));
        }
        return value;
    }

    public void clear() {
        entries.clear();
    }

    public String key(Object... parts) {
        return Arrays.stream(parts)
                .map(part -> Objects.toString(part, ""))
                .map(String::trim)
                .map(String::toLowerCase)
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
    }

    private void prune(Instant now) {
        if (entries.size() < MAX_ENTRIES) {
            return;
        }
        entries.entrySet().removeIf(entry -> now.isAfter(entry.getValue().expiresAt()));
        if (entries.size() >= MAX_ENTRIES) {
            entries.clear();
        }
    }

    private record CacheEntry(Object value, Instant expiresAt) {
    }
}
