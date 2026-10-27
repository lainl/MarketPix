package com.marketpix;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImageAnalysisServiceTests {

    private static final String TEST_API_KEY = "test-api-key";
    private static final String MODEL = "gpt-5.5";

    @TempDir
    Path tempDirectory;

    @Test
    void reportsHelpfulModelNotFoundErrorWithoutLeakingApiKey() throws Exception {
        AtomicReference<ClientRequest> request = new AtomicReference<>();
        ClientResponse response = ClientResponse.create(HttpStatus.NOT_FOUND)
                .header("Content-Type", "application/json")
                .body("{\"error\":{\"message\":\"Model unavailable for key " + TEST_API_KEY + "\"}}")
                .build();
        ImageAnalysisService service = service(response, request);

        ResponseStatusException exception = assertThrows(
                ResponseStatusException.class,
                () -> service.analyze(List.of(image("Front")), ""));

        assertEquals(HttpStatus.BAD_GATEWAY, exception.getStatusCode());
        assertTrue(exception.getReason().contains(MODEL));
        assertTrue(exception.getReason().contains("[redacted]"));
        assertFalse(exception.getReason().contains(TEST_API_KEY));
        assertEquals("/v1/chat/completions", request.get().url().getPath());
        assertEquals("Bearer " + TEST_API_KEY, request.get().headers().getFirst("Authorization"));
    }

    @Test
    void parsesImageAnalysisResponseFromConfiguredModel() throws Exception {
        String analysisJson = """
                {"primaryObject":"red square","sameObject":true,"images":[
                  {"imageNumber":1,"objectFound":true,"objectConfidence":0.99,
                   "matchesPrimaryObject":true,"reason":"one red square"},
                  {"imageNumber":2,"objectFound":true,"objectConfidence":0.99,
                   "matchesPrimaryObject":true,"reason":"same red square"}]}
                """;
        ImageAnalysisService service = service(chatResponse(analysisJson), new AtomicReference<>());

        ImageAnalysisService.ImageAnalysis result = service.analyze(List.of(image("Front"), image("Extra Angle 1")), "");

        assertEquals("red square", result.primaryObject());
        assertTrue(result.sameObject());
        assertEquals(2, result.images().size());
        assertEquals("Front", result.images().get(0).expectedAngle());
        assertTrue(result.images().get(1).matchesPrimaryObject());
    }

    @Test
    void parsesDetailCheck() throws Exception {
        ImageAnalysisService service = service(
                chatResponse("{\"textChanged\":true,\"partsChanged\":false,\"appearanceChanged\":false,\"clutterLeft\":false,\"issue\":\"VOLUME label invented\"}"),
                new AtomicReference<>());
        BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);

        ImageAnalysisService.DetailCheck check = service.verifyDetails(picture, picture, "cassette player");

        assertFalse(check.passed());
        assertEquals("text or markings differ", check.summary());
        assertEquals("VOLUME label invented", check.issue());
    }

    private static ClientResponse chatResponse(String content) throws Exception {
        String body = new ObjectMapper().writeValueAsString(Map.of(
                "choices", List.of(Map.of("message", Map.of("role", "assistant", "content", content)))));
        return ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private ImageAnalysisService service(ClientResponse response, AtomicReference<ClientRequest> capturedRequest) {
        ApiKeyConfiguration apiKeys = mock(ApiKeyConfiguration.class);
        when(apiKeys.getOpenAiApiKey()).thenReturn(TEST_API_KEY);
        WebClient.Builder webClientBuilder = WebClient.builder().exchangeFunction(request -> {
            capturedRequest.set(request);
            return Mono.just(response);
        });
        return new ImageAnalysisService(new OpenAiClient(webClientBuilder, new ObjectMapper(), apiKeys), MODEL);
    }

    private ImageJobService.JobImage image(String angle) throws Exception {
        Path imagePath = tempDirectory.resolve(angle.replace(' ', '_') + ".png");
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", imagePath.toFile());
        return new ImageJobService.JobImage(angle, "sample.png", "image/png", imagePath);
    }
}
