package com.marketpix.api;

import com.marketpix.ImageRecordService;
import com.marketpix.JpegZipExporter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/images/records")
public class ImageRecordController {

    private static final int MAX_DOWNLOAD_SETS = 200;

    private final ImageRecordService records;

    public ImageRecordController(ImageRecordService records) {
        this.records = records;
    }

    /** The gallery: saved sets newest first, filtered by inclusive dates (yyyy-MM-dd) and tags. */
    @GetMapping
    public Map<String, Object> search(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "tag", required = false) List<String> tags,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "48") int limit) {
        ImageRecordService.GalleryPage page = records.search(from, to, tags, offset, limit);
        return Map.of("items", page.items().stream().map(ImageRecordController::view).toList(),
                "total", page.total(), "tags", page.tags());
    }

    /** A saved set as the pages show it, with the links to its pictures and its ZIP. */
    public static Map<String, Object> view(ImageRecordService.GalleryItem item) {
        List<String> imageUrls = new ArrayList<>();
        for (int number = 1; number <= item.imageCount(); number++) {
            imageUrls.add("/api/images/records/" + item.id() + "/images/" + number);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", item.id());
        view.put("objectId", item.objectId());
        view.put("createdAt", item.createdAt().toString());
        view.put("tags", item.tags());
        view.put("imageUrls", imageUrls);
        view.put("downloadUrl", "/api/images/records/" + item.id() + "/download");
        return view;
    }

    /** Stored pictures never change, so the browser may keep them. */
    @GetMapping("/{id}/images/{number}")
    public ResponseEntity<byte[]> image(
            @PathVariable String id, @PathVariable int number, @RequestParam(defaultValue = "false") boolean download) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, (download ? "attachment" : "inline")
                        + "; filename=\"" + JpegZipExporter.entryName(id, number - 1) + "\"")
                .body(records.image(id, number));
    }

    /** Zips a saved set straight from storage, named like the original download. */
    @GetMapping("/{id}/download")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id) {
        int count = records.imageCount(id);
        StreamingResponseBody body = output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (int number = 1; number <= count; number++) {
                    zip.putNextEntry(new ZipEntry(JpegZipExporter.entryName(id, number - 1)));
                    zip.write(records.image(id, number));
                    zip.closeEntry();
                }
                zip.finish();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + id + ".zip\"")
                .body(body);
    }

    /** Zips the selected sets into one download, each set in a folder named after its id. */
    @GetMapping("/download")
    public ResponseEntity<StreamingResponseBody> downloadMany(@RequestParam("id") List<String> ids) {
        List<String> unique = ids.stream().distinct().toList();
        if (unique.isEmpty() || unique.size() > MAX_DOWNLOAD_SETS) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Select between 1 and " + MAX_DOWNLOAD_SETS + " sets to download.");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String id : unique) {
            counts.put(id, records.imageCount(id));
        }
        StreamingResponseBody body = output -> {
            try (ZipOutputStream zip = new ZipOutputStream(output)) {
                for (Map.Entry<String, Integer> set : counts.entrySet()) {
                    for (int number = 1; number <= set.getValue(); number++) {
                        zip.putNextEntry(new ZipEntry(set.getKey() + "/" + JpegZipExporter.entryName(set.getKey(), number - 1)));
                        zip.write(records.image(set.getKey(), number));
                        zip.closeEntry();
                    }
                }
                zip.finish();
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"MarketPix_" + counts.size() + "_sets.zip\"")
                .body(body);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        records.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Saves tags worked out from the user's own description of the object. */
    @PostMapping("/{id}/tags")
    public Map<String, Object> tag(@PathVariable String id, @RequestBody TagRequest request) {
        List<String> tags = records.updateTags(id, request.description());
        return ImageRecordService.tagsResponse(id, tags);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> failure(ResponseStatusException exception) {
        String message = exception.getReason() == null ? "The image record request failed." : exception.getReason();
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("message", message));
    }

    public record TagRequest(String description) {
    }
}
