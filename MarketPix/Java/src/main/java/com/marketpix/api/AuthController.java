package com.marketpix.api;

import com.marketpix.SessionInterceptor;
import com.marketpix.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final SessionService sessions;

    public AuthController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request) {
        return sessions.login(request.username(), request.password())
                .<ResponseEntity<Map<String, Object>>>map(session -> ResponseEntity.ok(Map.of(
                        "token", session.token(),
                        "expiresAt", session.expiresAt().toString())))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("message", "Username or password is incorrect.")));
    }

    /** Reached only with a valid token (see {@link SessionInterceptor}); lets a page check its session. */
    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request) {
        return sessions.touch(SessionInterceptor.token(request))
                .<Map<String, Object>>map(session -> Map.of("expiresAt", session.expiresAt().toString()))
                .orElse(Map.of());
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request) {
        sessions.logout(SessionInterceptor.token(request));
        return ResponseEntity.noContent().build();
    }

    public record LoginRequest(String username, String password) {
    }
}
