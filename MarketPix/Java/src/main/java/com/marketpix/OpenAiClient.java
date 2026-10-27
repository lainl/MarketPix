package com.marketpix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** Minimal OpenAI REST client shared by image analysis and image placement. */
@Component
public class OpenAiClient {

    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;

    public OpenAiClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper, ApiKeyConfiguration apiKeys) {
        this.webClient = webClientBuilder
                .baseUrl("https://api.openai.com")
                .clientConnector(new ReactorClientHttpConnector(HttpClient.create()
                        .responseTimeout(Duration.ofMinutes(5))))
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                        .build())
                .build();
        this.objectMapper = objectMapper;
        this.apiKey = apiKeys.getOpenAiApiKey();
    }

    /** Sends one user message (text and image parts) and parses the model's JSON reply. */
    public JsonNode chatJson(String model, List<Map<String, Object>> content, Duration timeout, String purpose) {
        requireKey(purpose);
        JsonNode response = webClient.post()
                .uri("/v1/chat/completions")
                .headers(headers -> headers.setBearerAuth(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", model,
                        "messages", List.of(Map.of("role", "user", "content", content)),
                        "response_format", Map.of("type", "json_object")))
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(body -> upstreamFailure(clientResponse.statusCode(), body, model, purpose)))
                .bodyToMono(JsonNode.class)
                .transform(mono -> withTransportErrors(mono, timeout, purpose))
                .block();

        String text = response == null
                ? ""
                : response.path("choices").path(0).path("message").path("content").asText("");
        if (text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI " + purpose + " returned no result.");
        }
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "OpenAI " + purpose + " returned invalid JSON.", exception);
        }
    }

    /**
     * Calls the image edit endpoint with one or more reference images (in order) and returns the
     * decoded PNG bytes of the first result.
     */
    public byte[] editImage(
            String model, List<byte[]> pngs, String prompt, String size, Duration timeout, String purpose) {
        requireKey(purpose);
        MultipartBodyBuilder body = new MultipartBodyBuilder();
        body.part("model", model);
        body.part("prompt", prompt);
        body.part("size", size);
        body.part("quality", "high");
        body.part("background", "opaque");
        body.part("output_format", "png");
        body.part("n", "1");
        for (int index = 0; index < pngs.size(); index++) {
            String filename = "image-" + (index + 1) + ".png";
            body.part("image[]", new ByteArrayResource(pngs.get(index)) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }).contentType(MediaType.IMAGE_PNG);
        }

        JsonNode response = webClient.post()
                .uri("/v1/images/edits")
                .headers(headers -> headers.setBearerAuth(apiKey))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .accept(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromMultipartData(body.build()))
                .retrieve()
                .onStatus(HttpStatusCode::isError, clientResponse -> clientResponse.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .map(text -> upstreamFailure(clientResponse.statusCode(), text, model, purpose)))
                .bodyToMono(JsonNode.class)
                .transform(mono -> withTransportErrors(mono, timeout, purpose))
                .block();

        String encoded = response == null ? "" : response.path("data").path(0).path("b64_json").asText("");
        if (encoded.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI " + purpose + " returned no image.");
        }
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "OpenAI " + purpose + " returned invalid image data.", exception);
        }
    }

    public static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    public static Map<String, Object> jpegImage(byte[] jpeg) {
        return Map.of("type", "image_url", "image_url", Map.of(
                "url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(jpeg),
                "detail", "high"));
    }

    private void requireKey(String purpose) {
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "OpenAI " + purpose + " is not configured. Set openai.api.key-file in application.properties.");
        }
    }

    private <T> Mono<T> withTransportErrors(Mono<T> mono, Duration timeout, String purpose) {
        return mono.timeout(timeout)
                .onErrorMap(WebClientRequestException.class, exception -> new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Could not connect to OpenAI for " + purpose + ".", exception))
                .onErrorMap(TimeoutException.class, exception -> new ResponseStatusException(
                        HttpStatus.GATEWAY_TIMEOUT, "OpenAI " + purpose + " timed out. Please retry.", exception));
    }

    private ResponseStatusException upstreamFailure(HttpStatusCode status, String body, String model, String purpose) {
        String message = switch (status.value()) {
            case 400 -> "OpenAI rejected the " + purpose + " request.";
            case 401, 403 -> "OpenAI rejected the configured API key or access to model '" + model + "'.";
            case 404 -> "OpenAI model '" + model + "' was not found. Check the model setting for " + purpose + ".";
            case 429 -> "OpenAI " + purpose + " quota or rate limit was exceeded.";
            default -> "OpenAI " + purpose + " failed with HTTP " + status.value() + ".";
        };

        String providerMessage = providerErrorMessage(body).replaceAll("\\s+", " ").trim();
        if (!providerMessage.isBlank()) {
            if (!apiKey.isBlank()) {
                providerMessage = providerMessage.replace(apiKey, "[redacted]");
            }
            if (providerMessage.length() > 300) {
                providerMessage = providerMessage.substring(0, 300) + "...";
            }
            message += " " + providerMessage;
        }
        HttpStatus mapped = status.is5xxServerError() || status.value() == 429
                ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_GATEWAY;
        return new ResponseStatusException(mapped, message);
    }

    private String providerErrorMessage(String body) {
        if (body.isBlank()) {
            return "";
        }
        try {
            return objectMapper.readTree(body).path("error").path("message").asText("");
        } catch (JsonProcessingException exception) {
            return "";
        }
    }
}
