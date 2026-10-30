package com.marketpix.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketpix.ChatService;
import com.marketpix.PhotoSearchService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final PhotoSearchService photoSearch;
    private final ObjectMapper objectMapper;

    public ChatController(ChatService chatService, PhotoSearchService photoSearch, ObjectMapper objectMapper) {
        this.chatService = chatService;
        this.photoSearch = photoSearch;
        this.objectMapper = objectMapper;
    }

    /**
     * A request for saved photos is answered from the gallery: the text, then a {@code photos} event with the
     * matching sets. Anything else streams the assistant's reply.
     */
    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ChatRequest request) {
        Optional<PhotoSearchService.Result> photos = photoSearch.answer(request.messages());
        if (photos.isPresent()) {
            return Flux.just(
                    ServerSentEvent.<String>builder(photos.get().text()).build(),
                    ServerSentEvent.<String>builder(photosJson(photos.get())).event("photos").build());
        }
        return chatService.streamResponse(request)
                .map(text -> ServerSentEvent.<String>builder(text).build())
                .doOnError(exception ->
                        LOGGER.error("AI chat stream failed ({})", exception.getClass().getSimpleName()))
                .onErrorResume(exception ->
                        Flux.just(ServerSentEvent.<String>builder(
                                        "Chat response failed. Please try again.")
                                .event("error")
                                .build()));
    }

    private String photosJson(PhotoSearchService.Result result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", result.items().stream().map(ImageRecordController::view).toList());
        body.put("total", result.total());
        body.put("galleryLink", result.galleryLink());
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
