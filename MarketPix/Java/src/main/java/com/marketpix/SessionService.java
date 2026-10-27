package com.marketpix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Issues and checks login session tokens. Tokens live in memory and expire after a period without
 * use, so a restart signs everyone out.
 */
@Service
public class SessionService {

    private static final Duration IDLE_TIMEOUT = Duration.ofHours(12);
    private static final int TOKEN_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Instant> sessions = new ConcurrentHashMap<>();
    private final String username;
    private final String password;

    public SessionService(
            @Value("${marketpix.auth.username:0}") String username,
            @Value("${marketpix.auth.password:0}") String password) {
        this.username = username;
        this.password = password;
    }

    /** Returns a new session when the credentials match, otherwise empty. */
    public Optional<Session> login(String username, String password) {
        // Both comparisons always run so the response time does not reveal which one failed.
        boolean usernameMatches = matches(this.username, username);
        boolean passwordMatches = matches(this.password, password);
        if (!usernameMatches || !passwordMatches) {
            return Optional.empty();
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(IDLE_TIMEOUT);
        sessions.put(token, expiresAt);
        return Optional.of(new Session(token, expiresAt));
    }

    /** Checks a token and, when it is valid, extends it by the idle timeout. */
    public Optional<Session> touch(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Instant expiresAt = sessions.computeIfPresent(token, (key, current) ->
                now.isAfter(current) ? null : now.plus(IDLE_TIMEOUT));
        return expiresAt == null ? Optional.empty() : Optional.of(new Session(token, expiresAt));
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    @Scheduled(fixedDelay = 600_000)
    public void cleanExpiredSessions() {
        Instant now = Instant.now();
        sessions.entrySet().removeIf(entry -> now.isAfter(entry.getValue()));
    }

    private static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    public record Session(String token, Instant expiresAt) {
    }
}
