package com.marketpix;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Behind Caddy the app is reached over plain HTTP while the browser's page is HTTPS. These run a real
 * server, because the forwarded headers are applied by Tomcat, below what MockMvc sees.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.config.import=classpath:test.env[.properties]")
class ReverseProxyTests {

    private final HttpClient http = HttpClient.newHttpClient();

    @LocalServerPort
    private int port;

    @Test
    void aLoginFromTheHttpsSiteIsSameOriginBehindTheProxy() throws Exception {
        assertEquals(200, login("https", "https://localhost"));
    }

    @Test
    void anUnknownSiteIsStillRejected() throws Exception {
        assertEquals(403, login("https", "https://evil.example"));
    }

    @Test
    void theInternalStatusIsHiddenFromForwardedRequests() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/internal/status"))
                .header("X-Forwarded-For", "203.0.113.7")
                .header("X-Forwarded-Proto", "https")
                .GET()
                .build();

        assertEquals(404, http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    private int login(String forwardedProto, String origin) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                .header("Content-Type", "application/json")
                .header("Origin", origin)
                .header("X-Forwarded-For", "198.51.100.20")
                .header("X-Forwarded-Proto", forwardedProto)
                .POST(HttpRequest.BodyPublishers.ofString("{\"username\":\"0\",\"password\":\"0\"}"))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
