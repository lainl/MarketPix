package com.marketpix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketpix.api.ChatRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {

    private static final String MARKETPIX_CHAT_POLICY = """
            You are MarketPix, a concise assistant for auctioneers preparing listing photos of auction lots.
            Your only supported chat capability is helping the user explain or organize extra context
            for a product image request (for example what the item is, which part is the item, or which
            angle a picture shows). Understand what the user means, ask a short clarifying question
            when the context is unclear, and do not invent product details.
            You cannot search an item catalog, retrieve uploads, access databases, upload items, or generate
            images. For any request to perform those or any other unsupported action, reply with exactly:
            Because this is a demo, this feature isn't yet available.
            Do not claim you performed an action or generated an image. Image processing is handled
            separately by the MarketPix image pipeline when the user clicks Process.
            """;

    private static final ParameterizedTypeReference<ServerSentEvent<String>> SSE_TYPE =
            new ParameterizedTypeReference<>() { };

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String model;

    public ChatService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            ApiKeyConfiguration apiKeys,
            @Value("${openai.chat.model:gpt-5.4-mini}") String model) {
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder.baseUrl("https://api.openai.com").build();
        this.apiKey = apiKeys.getOpenAiApiKey();
        this.model = model;
    }

    public Flux<String> streamResponse(ChatRequest request) {
        if (apiKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "AI chat is not configured. Set OPENAI_API_KEY.");
        }

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", MARKETPIX_CHAT_POLICY));
        request.messages().forEach(message -> messages.add(Map.of(
                "role", message.role().equals("model") ? "assistant" : message.role(),
                "content", message.content())));

        return webClient.post()
                .uri("/v1/chat/completions")
                .headers(headers -> headers.setBearerAuth(apiKey))
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(Map.of(
                        "model", model,
                        "stream", true,
                        "messages", messages))
                .retrieve()
                .onStatus(status -> status.isError(), response ->
                        response.createException().map(exception ->
                                new ResponseStatusException(
                                        HttpStatus.BAD_GATEWAY,
                                        "OpenAI could not complete the chat request.",
                                        exception)))
                .bodyToFlux(SSE_TYPE)
                .map(event -> event.data())
                .filter(data -> data != null && !data.isBlank() && !data.equals("[DONE]"))
                .concatMap(this::extractText);
    }

    private Flux<String> extractText(String data) {
        try {
            JsonNode choices = objectMapper.readTree(data).path("choices");
            if (!choices.isArray()) {
                return Flux.empty();
            }

            List<String> textParts = new ArrayList<>();
            for (JsonNode choice : choices) {
                JsonNode content = choice.path("delta").path("content");
                if (content.isTextual()) {
                    textParts.add(content.asText());
                } else if (content.isArray()) {
                    for (JsonNode part : content) {
                        JsonNode text = part.path("text");
                        if (text.isTextual()) {
                            textParts.add(text.asText());
                        }
                    }
                }
            }
            return Flux.fromIterable(textParts);
        } catch (JsonProcessingException exception) {
            return Flux.error(new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "OpenAI returned an invalid streaming response.",
                    exception));
        }
    }
}
