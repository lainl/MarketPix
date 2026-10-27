package com.marketpix.api;

import com.marketpix.ImageJobEvent;
import com.marketpix.ImageJobService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@RestController
@RequestMapping("/api/images/jobs")
public class ImageJobController {

    private static final Logger LOGGER = Logger.getLogger(ImageJobController.class.getName());
    private final ImageJobService jobService;
    private final boolean backgroundEnabled;

    public ImageJobController(
            ImageJobService jobService,
            @Value("${marketpix.background.enabled:true}") boolean backgroundEnabled) {
        this.jobService = jobService;
        this.backgroundEnabled = backgroundEnabled;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ImageJobEvent>> createJob(
            @RequestParam(value = "angle", required = false) List<String> angles,
            @RequestParam(value = "images", required = false) List<MultipartFile> images,
            @RequestParam(value = "background", required = false) MultipartFile background,
            @RequestParam(value = "context", defaultValue = "") String context) {
        // When background scenes are switched off, every picture goes on white.
        MultipartFile scene = backgroundEnabled ? background : null;
        return stream(events -> jobService.createJob(angles, images, scene, context, events));
    }

    @PostMapping(
            path = "/{jobId}/confirm",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ImageJobEvent>> confirmJob(
            @PathVariable UUID jobId,
            @RequestBody ConfirmRequest request) {
        return stream(events -> jobService.confirmJob(
                jobId,
                request.ready(),
                request.context(),
                events));
    }

    @GetMapping("/{jobId}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID jobId) {
        String filename = jobService.getDownloadFilename(jobId);
        Resource archive = new FileSystemResource(jobService.getDownload(jobId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(archive);
    }

    @GetMapping("/{jobId}/images/{imageNumber}")
    public ResponseEntity<Resource> preview(@PathVariable UUID jobId, @PathVariable int imageNumber) {
        Resource image = new FileSystemResource(jobService.getPreview(jobId, imageNumber));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + jobService.getPreviewFilename(jobId, imageNumber) + "\"")
                .body(image);
    }

    private Flux<ServerSentEvent<ImageJobEvent>> stream(Consumer<Consumer<ImageJobEvent>> operation) {
        return Flux.create(sink -> Schedulers.boundedElastic().schedule(() -> {
            try {
                operation.accept(event -> sink.next(
                        ServerSentEvent.<ImageJobEvent>builder(event)
                                .event(event.status().toLowerCase())
                                .build()));
            } catch (ResponseStatusException exception) {
                String message = exception.getReason() == null
                        ? "The image job could not be completed."
                        : exception.getReason();
                sink.next(ServerSentEvent.<ImageJobEvent>builder(new ImageJobEvent(
                                "FAILED", message, null, null, 0, List.of(), null, null, List.of(), false))
                        .event("failed")
                        .build());
            } catch (RuntimeException exception) {
                LOGGER.log(Level.SEVERE, "Unhandled image-job request failure", exception);
                sink.next(ServerSentEvent.<ImageJobEvent>builder(new ImageJobEvent(
                                "FAILED", "The image job could not be completed. Please retry.", null, null, 0,
                                List.of(), null, null, List.of(), false))
                        .event("failed")
                        .build());
            } finally {
                sink.complete();
            }
        }));
    }

    public record ConfirmRequest(boolean ready, String context) {
    }
}
