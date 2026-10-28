package com.marketpix.api;

import com.marketpix.BulkJobService;
import com.marketpix.BulkJobView;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/bulk/jobs")
public class BulkJobController {

    private final BulkJobService bulkJobs;
    private final boolean backgroundEnabled;

    public BulkJobController(
            BulkJobService bulkJobs,
            @Value("${marketpix.background.enabled}") boolean backgroundEnabled) {
        this.bulkJobs = bulkJobs;
        this.backgroundEnabled = backgroundEnabled;
    }

    /** Lists the shared Dropbox folder and returns the photo count and estimated cost to confirm. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public BulkJobView create(@RequestBody CreateRequest request) {
        return bulkJobs.create(request.dropboxUrl());
    }

    /** The same, with an optional background scene for every photo (ignored when backgrounds are off). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public BulkJobView createWithBackground(
            @RequestParam("dropboxUrl") String dropboxUrl,
            @RequestParam(value = "background", required = false) MultipartFile background) {
        return bulkJobs.create(dropboxUrl, backgroundEnabled ? background : null);
    }

    @GetMapping("/{jobId}")
    public BulkJobView status(@PathVariable UUID jobId) {
        return bulkJobs.status(jobId);
    }

    @PostMapping("/{jobId}/start")
    public BulkJobView start(@PathVariable UUID jobId) {
        return bulkJobs.start(jobId);
    }

    @PostMapping("/{jobId}/cancel")
    public BulkJobView cancel(@PathVariable UUID jobId) {
        return bulkJobs.cancel(jobId);
    }

    @GetMapping("/{jobId}/download")
    public ResponseEntity<Resource> download(@PathVariable UUID jobId) {
        Resource archive = new FileSystemResource(bulkJobs.getDownload(jobId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + bulkJobs.getDownloadFilename(jobId) + "\"")
                .body(archive);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> failure(ResponseStatusException exception) {
        String message = exception.getReason() == null ? "The bulk job request failed." : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
    }

    public record CreateRequest(String dropboxUrl) {
    }
}
