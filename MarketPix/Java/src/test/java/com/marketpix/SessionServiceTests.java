package com.marketpix;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionServiceTests {

    @TempDir
    Path directory;

    @Test
    void aLoginSurvivesARestart() throws Exception {
        Path file = directory.resolve("private/sessions.json");
        String token = service("user", "secret", file).login("user", "secret").orElseThrow().token();

        assertTrue(service("user", "secret", file).touch(token).isPresent());
        assertFalse(Files.readString(file).contains(token), "the file must hold only token hashes");
    }

    @Test
    void loggingOutIsRemembered() {
        Path file = directory.resolve("sessions.json");
        SessionService first = service("user", "secret", file);
        String token = first.login("user", "secret").orElseThrow().token();
        first.logout(token);

        assertFalse(service("user", "secret", file).touch(token).isPresent());
    }

    @Test
    void changingThePasswordSignsEveryoneOut() {
        Path file = directory.resolve("sessions.json");
        String token = service("user", "secret", file).login("user", "secret").orElseThrow().token();

        assertFalse(service("user", "changed", file).touch(token).isPresent());
    }

    @Test
    void wrongCredentialsGetNoSession() {
        assertFalse(service("user", "secret", directory.resolve("sessions.json")).login("user", "wrong").isPresent());
    }

    private static SessionService service(String username, String password, Path file) {
        return new SessionService(username, password, 30, file.toString(), new ObjectMapper());
    }
}
