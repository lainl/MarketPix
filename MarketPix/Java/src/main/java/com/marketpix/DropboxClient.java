package com.marketpix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;

/**
 * Minimal Dropbox REST client for reading folders that people share with the MarketPix Dropbox
 * account. Folders are read through their shared link, so the link works as soon as the folder is
 * shared with that account.
 */
@Component
public class DropboxClient {

    private static final Logger LOGGER = Logger.getLogger(DropboxClient.class.getName());
    private static final String API = "https://api.dropboxapi.com";
    private static final String CONTENT = "https://content.dropboxapi.com";
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String accessToken;
    private final String refreshToken;
    private final String appKey;
    private final String appSecret;
    private final String accountEmail;
    private String cachedAccessToken;
    private Instant cachedAccessTokenExpiry = Instant.EPOCH;

    public DropboxClient(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            @Value("${dropbox.access-token:}") String accessToken,
            @Value("${dropbox.refresh-token:}") String refreshToken,
            @Value("${dropbox.app-key:}") String appKey,
            @Value("${dropbox.app-secret:}") String appSecret,
            @Value("${dropbox.account-email:marketpixs@gmail.com}") String accountEmail) {
        this.webClient = webClientBuilder
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(TIMEOUT)))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.accessToken = accessToken.trim();
        this.refreshToken = refreshToken.trim();
        this.appKey = appKey.trim();
        this.appSecret = appSecret.trim();
        this.accountEmail = accountEmail;
    }

    public String getAccountEmail() {
        return accountEmail;
    }

    /** Lists the files directly inside a shared folder link, sorted by name. */
    public SharedFolder listSharedFolder(String url) {
        JsonNode metadata = postJson("/2/sharing/get_shared_link_metadata", Map.of("url", url));
        if (!"folder".equals(metadata.path(".tag").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "That link points to a single file. Enter the link of a Dropbox folder.");
        }

        List<SharedFile> files = new ArrayList<>();
        JsonNode page = postJson("/2/files/list_folder", Map.of("path", "", "shared_link", Map.of("url", url)));
        while (true) {
            for (JsonNode entry : page.path("entries")) {
                if ("file".equals(entry.path(".tag").asText())) {
                    files.add(new SharedFile(entry.path("name").asText(), entry.path("size").asLong()));
                }
            }
            if (!page.path("has_more").asBoolean(false)) {
                break;
            }
            page = postJson("/2/files/list_folder/continue", Map.of("cursor", page.path("cursor").asText()));
        }
        files.sort(Comparator.comparing(SharedFile::name, String.CASE_INSENSITIVE_ORDER));
        return new SharedFolder(metadata.path("name").asText("Dropbox folder"), List.copyOf(files));
    }

    /** Downloads one file from a shared folder link. */
    public byte[] downloadSharedFile(String url, String fileName) {
        String argument = apiArgument(Map.of("url", url, "path", "/" + fileName));
        return webClient.post()
                .uri(CONTENT + "/2/sharing/get_shared_link_file")
                .headers(headers -> headers.setBearerAuth(currentAccessToken()))
                .header("Dropbox-API-Arg", argument)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> upstreamFailure(response.statusCode(), body)))
                .bodyToMono(byte[].class)
                .transform(this::withTransportErrors)
                .blockOptional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dropbox returned an empty file."));
    }

    private JsonNode postJson(String path, Map<String, Object> body) {
        return webClient.post()
                .uri(API + path)
                .headers(headers -> headers.setBearerAuth(currentAccessToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(text -> upstreamFailure(response.statusCode(), text)))
                .bodyToMono(JsonNode.class)
                .transform(this::withTransportErrors)
                .blockOptional()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dropbox returned no result."));
    }

    /**
     * Uses the long-lived refresh token when it is configured (Dropbox access tokens expire after a
     * few hours), otherwise the fixed access token.
     */
    private synchronized String currentAccessToken() {
        if (refreshToken.isEmpty()) {
            if (accessToken.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Dropbox is not configured. Set DROPBOX_REFRESH_TOKEN, DROPBOX_APP_KEY and DROPBOX_APP_SECRET on the server.");
            }
            return accessToken;
        }
        if (cachedAccessToken != null && Instant.now().isBefore(cachedAccessTokenExpiry)) {
            return cachedAccessToken;
        }
        JsonNode token = webClient.post()
                .uri(API + "/oauth2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData("grant_type", "refresh_token")
                        .with("refresh_token", refreshToken)
                        .with("client_id", appKey)
                        .with("client_secret", appSecret))
                .retrieve()
                .onStatus(HttpStatusCode::isError, response -> Mono.just(new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Dropbox rejected MarketPix's refresh token. Check the Dropbox settings on the server.")))
                .bodyToMono(JsonNode.class)
                .transform(this::withTransportErrors)
                .block();
        String value = token == null ? "" : token.path("access_token").asText("");
        if (value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Dropbox did not return an access token.");
        }
        cachedAccessToken = value;
        // Refresh five minutes early so a long download never starts with a token about to expire.
        cachedAccessTokenExpiry = Instant.now().plusSeconds(Math.max(60, token.path("expires_in").asLong(14_400) - 300));
        return cachedAccessToken;
    }

    /** Dropbox-API-Arg is a JSON header, so every non-ASCII character must be escaped. */
    private String apiArgument(Map<String, Object> argument) {
        String json;
        try {
            json = objectMapper.writeValueAsString(argument);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
        StringBuilder escaped = new StringBuilder(json.length());
        for (char character : json.toCharArray()) {
            if (character > 0x7e) {
                escaped.append(String.format("\\u%04x", (int) character));
            } else {
                escaped.append(character);
            }
        }
        return escaped.toString();
    }

    private <T> Mono<T> withTransportErrors(Mono<T> mono) {
        return mono.timeout(TIMEOUT)
                .onErrorMap(WebClientRequestException.class, exception -> new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Could not connect to Dropbox.", exception))
                .onErrorMap(TimeoutException.class, exception -> new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT, "Dropbox timed out. Please retry.", exception));
    }

    private ResponseStatusException upstreamFailure(HttpStatusCode status, String body) {
        String summary = errorSummary(body);
        if (status.value() == 409 && (summary.startsWith("shared_link_not_found")
                || summary.startsWith("shared_link_access_denied")
                || summary.startsWith("access_denied")
                || summary.startsWith("path/not_found")
                || summary.startsWith("email_not_verified"))) {
            return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "MarketPix can't open that Dropbox folder. Check the link and make sure the folder is shared with "
                            + accountEmail + ".");
        }
        String detail = summary.isBlank() ? body.replaceAll("\\s+", " ").trim() : summary;
        LOGGER.warning("Dropbox request failed with HTTP " + status.value() + ": "
                + (detail.length() > 300 ? detail.substring(0, 300) + "..." : detail));
        if (summary.isBlank() && body.contains("missing_scope")) {
            summary = "missing_scope";
        }
        if (status.value() == 401 && summary.startsWith("expired_access_token")) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Dropbox access token has expired. Set dropbox.refresh-token on the server so MarketPix can renew it.");
        }
        if (status.value() == 401 && summary.startsWith("missing_scope")) {
            return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The Dropbox app is missing a permission. In the Dropbox App Console, enable files.metadata.read, "
                            + "files.content.read and sharing.read, then generate a new token.");
        }
        String message = switch (status.value()) {
            case 400 -> "Dropbox rejected the request. Check that the link is a Dropbox folder link.";
            case 401 -> "Dropbox rejected MarketPix's credentials. Check the Dropbox settings on the server.";
            case 429 -> "Dropbox is rate limiting MarketPix. Wait a minute and retry.";
            default -> "Dropbox failed with HTTP " + status.value() + (summary.isBlank() ? "." : " (" + summary + ").");
        };
        HttpStatus mapped = status.value() == 400 ? HttpStatus.BAD_REQUEST
                : status.value() == 401 || status.value() == 429 || status.is5xxServerError()
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_GATEWAY;
        return new ResponseStatusException(mapped, message);
    }

    private String errorSummary(String body) {
        if (body.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readTree(body).path("error_summary").asText("");
        } catch (JsonProcessingException exception) {
            return "";
        }
    }

    public record SharedFolder(String name, List<SharedFile> files) {
    }

    public record SharedFile(String name, long size) {
    }
}
