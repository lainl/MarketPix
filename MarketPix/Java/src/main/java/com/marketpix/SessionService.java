package com.marketpix;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Issues and checks login session tokens. A session expires after a period without use. Sessions are kept
 * in a private file on this machine ({@code marketpix.auth.session-file}), so a restart does not sign
 * anyone out; the file holds only SHA-256 hashes of the tokens, never the tokens themselves. Changing the
 * username or password signs everyone out.
 */
@Service
public class SessionService {

    private static final Logger LOGGER = Logger.getLogger(SessionService.class.getName());
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_SESSIONS = 50;

    private final SecureRandom random = new SecureRandom();
    // Token hash -> expiry.
    private final ConcurrentHashMap<String, Instant> sessions = new ConcurrentHashMap<>();
    private final String username;
    private final String password;
    private final Duration idleTimeout;
    private final Path file;
    private final ObjectMapper objectMapper;
    private final String credentials;
    private volatile boolean dirty;

    public SessionService(
            @Value("${marketpix.auth.username}") String username,
            @Value("${marketpix.auth.password}") String password,
            @Value("${marketpix.auth.session-days}") long sessionDays,
            @Value("${marketpix.auth.session-file}") String sessionFile,
            ObjectMapper objectMapper) {
        this.username = username;
        this.password = password;
        this.idleTimeout = Duration.ofDays(Math.max(1, sessionDays));
        this.file = sessionFile == null || sessionFile.isBlank() ? null : Path.of(sessionFile.trim());
        this.objectMapper = objectMapper;
        this.credentials = sha256(username + "\n" + password);
        load();
    }

    /** Returns a new session when the credentials match, otherwise empty. */
    public Optional<Session> login(String username, String password) {
        // Both comparisons always run so the response time does not reveal which one failed.
        boolean usernameMatches = matches(this.username, username);
        boolean passwordMatches = matches(this.password, password);
        if (!usernameMatches || !passwordMatches) {
            LOGGER.warning("A login attempt failed: wrong username or password.");
            return Optional.empty();
        }
        byte[] bytes = new byte[TOKEN_BYTES];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        Instant expiresAt = Instant.now().plus(idleTimeout);
        synchronized (sessions) {
            while (sessions.size() >= MAX_SESSIONS) {
                sessions.entrySet().stream().min(Map.Entry.comparingByValue())
                        .ifPresent(oldest -> sessions.remove(oldest.getKey()));
            }
            sessions.put(sha256(token), expiresAt);
        }
        save();
        LOGGER.info("Logged in; " + sessions.size() + " active session(s).");
        return Optional.of(new Session(token, expiresAt));
    }

    /** Checks a token and, when it is valid, extends it by the idle timeout. */
    public Optional<Session> touch(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Instant expiresAt = sessions.computeIfPresent(sha256(token), (key, current) ->
                now.isAfter(current) ? null : now.plus(idleTimeout));
        if (expiresAt == null) {
            return Optional.empty();
        }
        // Written by the next flush; a lost extension only shortens a session that still has weeks left.
        dirty = true;
        return Optional.of(new Session(token, expiresAt));
    }

    public void logout(String token) {
        if (token != null && sessions.remove(sha256(token)) != null) {
            save();
            LOGGER.info("Logged out; " + sessions.size() + " active session(s).");
        }
    }

    @Scheduled(fixedDelay = 600_000)
    public void cleanExpiredSessions() {
        Instant now = Instant.now();
        if (sessions.entrySet().removeIf(entry -> now.isAfter(entry.getValue()))) {
            dirty = true;
        }
        flush();
    }

    @Scheduled(fixedDelay = 60_000)
    @PreDestroy
    public void flush() {
        if (dirty) {
            save();
        }
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        try {
            JsonNode stored = objectMapper.readTree(file.toFile());
            if (!credentials.equals(stored.path("credentials").asText())) {
                LOGGER.info("The login changed, so the saved sessions were discarded.");
                dirty = true;
                return;
            }
            Instant now = Instant.now();
            stored.path("sessions").fields().forEachRemaining(entry -> {
                try {
                    Instant expiresAt = Instant.parse(entry.getValue().asText());
                    if (expiresAt.isAfter(now)) {
                        sessions.put(entry.getKey(), expiresAt);
                    }
                } catch (DateTimeParseException exception) {
                    // Skipped.
                }
            });
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not read the saved sessions in " + file + "; starting without them.", exception);
        }
    }

    /** Replaces the file in one step so a crash never leaves it half written. */
    private synchronized void save() {
        dirty = false;
        if (file == null) {
            return;
        }
        ObjectNode stored = objectMapper.createObjectNode();
        stored.put("credentials", credentials);
        ObjectNode entries = stored.putObject("sessions");
        sessions.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> entries.put(entry.getKey(), entry.getValue().toString()));
        try {
            Path directory = file.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            restrictToOwner(directory, "rwx------");
            Path temporary = Files.createTempFile(directory, "sessions-", ".tmp");
            restrictToOwner(temporary, "rw-------");
            Files.writeString(temporary, objectMapper.writeValueAsString(stored), StandardCharsets.UTF_8);
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            dirty = true;
            LOGGER.log(Level.WARNING, "Could not save the sessions to " + file, exception);
        }
    }

    /** On Linux and macOS only the server's user may read the sessions; Windows user folders are private already. */
    private static void restrictToOwner(Path path, String permissions) throws IOException {
        PosixFileAttributeView view = Files.getFileAttributeView(path, PosixFileAttributeView.class);
        if (view != null) {
            view.setPermissions(PosixFilePermissions.fromString(permissions));
        }
    }

    private static boolean matches(String expected, String actual) {
        return actual != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public record Session(String token, Instant expiresAt) {
    }
}
