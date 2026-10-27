package com.marketpix.api;

import com.marketpix.ChatService;
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

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping(path = "/stream", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamChat(@Valid @RequestBody ChatRequest request) {
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
}
