package com.marketpix.api;

import com.marketpix.ImageRecordService;
import com.marketpix.JpegZipExporter;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
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
        List<Map<String, Object>> items = new ArrayList<>();
        for (ImageRecordService.GalleryItem item : page.items()) {
            List<String> imageUrls = new ArrayList<>();
            for (int number = 1; number <= item.imageCount(); number++) {
                imageUrls.add("/api/images/records/" + item.id() + "/images/" + number);
            }
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("id", item.id());
            view.put("createdAt", item.createdAt().toString());
            view.put("tags", item.tags());
            view.put("imageUrls", imageUrls);
            view.put("downloadUrl", "/api/images/records/" + item.id() + "/download");
            items.add(view);
        }
        return Map.of("items", items, "total", page.total(), "tags", page.tags());
    }

    /** Stored pictures never change, so the browser may keep them. */
    @GetMapping("/{id}/images/{number}")
    public ResponseEntity<byte[]> image(@PathVariable String id, @PathVariable int number) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + JpegZipExporter.entryName(id, number - 1) + "\"")
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

    /** Saves tags worked out from the user's own description of the object. */
    @PostMapping("/{id}/tags")
    public Map<String, Object> tag(@PathVariable String id, @RequestBody TagRequest request) {
        List<String> tags = records.updateTags(id, request.description());
        return ImageRecordService.tagsResponse(id, tags);
    }

    public record TagRequest(String description) {
    }
}
