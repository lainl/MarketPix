package com.marketpix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps finished images in Cloudflare R2. Each set lives under its id: the JPEGs as
 * {@code <id>/<id>_01.jpg, ...} and a {@code <id>/record.json} holding the id, the time it was saved and the
 * array of tags the user described it with.
 *
 * <p>The gallery searches an in-memory copy of every record. It is filled from the bucket on the first
 * search, kept current by {@link #store} and {@link #updateTags}, and re-listed every few minutes so sets
 * saved by another server instance show up too.
 */
@Service
public class ImageRecordService {

    private static final Logger LOGGER = Logger.getLogger(ImageRecordService.class.getName());
    private static final int MAX_TAGS = 20;
    private static final int MAX_TAG_LENGTH = 40;
    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_TAG_FACETS = 60;
    private static final int INDEX_THREADS = 8;
    private static final Duration INDEX_MAX_AGE = Duration.ofMinutes(3);
    private static final String TAG_PROMPT = """
            An auctioneer described an auction lot in their own words. Turn the description into short search
            tags: the kind of object (for example Chair), plus its notable colors, style, origin, era,
            material or maker when they mention them. Use one to three words per tag, no sentences, no
            duplicates, and do not invent anything they did not say. Return only JSON:
            {"tags":["Chair","Red","American"]}
            Description: %s
            """;

    private final R2Client r2;
    private final OpenAiClient openAi;
    private final ObjectMapper objectMapper;
    private final String chatModel;
    private final ZoneId zone;
    private final Map<String, GalleryItem> index = new ConcurrentHashMap<>();
    private volatile Instant indexedAt;

    public ImageRecordService(
            R2Client r2,
            OpenAiClient openAi,
            ObjectMapper objectMapper,
            @Value("${openai.chat.model:gpt-5.4-mini}") String chatModel,
            @Value("${marketpix.id.timezone:}") String timezone) {
        this.r2 = r2;
        this.openAi = openAi;
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
        this.zone = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
    }

    /** One saved set as the gallery shows it. */
    public record GalleryItem(String id, Instant createdAt, List<String> tags, int imageCount) {
    }

    public record TagCount(String tag, int count) {
    }

    /** One page of matches, the number of matches in all, and the tags found across every match. */
    public record GalleryPage(List<GalleryItem> items, int total, List<TagCount> tags) {
    }

    public boolean isEnabled() {
        return r2.isConfigured();
    }

    /** Uploads the finished JPEGs and a record with no tags yet. Callers check {@link #isEnabled()} first. */
    public void store(String id, List<Path> jpegs) {
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < jpegs.size(); index++) {
            String key = id + "/" + JpegZipExporter.entryName(id, index);
            try {
                r2.put(key, Files.readAllBytes(jpegs.get(index)), "image/jpeg");
            } catch (IOException exception) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Could not read a finished picture for storage.", exception);
            }
            keys.add(key);
        }
        ObjectNode record = objectMapper.createObjectNode();
        record.put("id", id);
        record.put("createdAt", Instant.now().toString());
        record.putArray("tags");
        ArrayNode images = record.putArray("images");
        keys.forEach(images::add);
        putRecord(id, record);
        indexRecord(record);
    }

    /** Turns the user's free-text description into tags and saves them on the record. */
    public List<String> updateTags(String id, String description) {
        if (!ImageIdGenerator.ID_PATTERN.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "That is not a valid image id.");
        }
        if (description == null || description.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Describe the object first.");
        }
        if (!r2.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cloud image storage is not configured.");
        }
        ObjectNode record = readRecord(id);
        List<String> tags = formatTags(description.trim());
        ArrayNode array = record.putArray("tags");
        tags.forEach(array::add);
        putRecord(id, record);
        indexRecord(record);
        return tags;
    }

    /**
     * Finds saved sets, newest first. {@code from} and {@code to} are inclusive days in the id time zone and
     * either may be null; every given tag must be on a set for it to match (ignoring case).
     */
    public GalleryPage search(LocalDate from, LocalDate to, List<String> tags, int offset, int limit) {
        if (!r2.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Cloud image storage is not configured.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The start date is after the end date.");
        }
        refreshIndexIfStale();
        return filter(index.values(), zone, from, to, tags, offset, limit);
    }

    static GalleryPage filter(
            Collection<GalleryItem> items, ZoneId zone, LocalDate from, LocalDate to, List<String> tags,
            int offset, int limit) {
        Set<String> wanted = new HashSet<>();
        if (tags != null) {
            for (String tag : tags) {
                if (tag != null && !tag.isBlank()) {
                    wanted.add(tag.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        List<GalleryItem> matches = new ArrayList<>();
        for (GalleryItem item : items) {
            LocalDate day = item.createdAt().atZone(zone).toLocalDate();
            if ((from != null && day.isBefore(from)) || (to != null && day.isAfter(to))) {
                continue;
            }
            Set<String> itemTags = new HashSet<>();
            item.tags().forEach(tag -> itemTags.add(tag.toLowerCase(Locale.ROOT)));
            if (itemTags.containsAll(wanted)) {
                matches.add(item);
            }
        }
        matches.sort(Comparator.comparing(GalleryItem::createdAt).reversed().thenComparing(GalleryItem::id));

        Map<String, Integer> counts = new HashMap<>();
        for (GalleryItem item : matches) {
            item.tags().forEach(tag -> counts.merge(tag, 1, Integer::sum));
        }
        List<TagCount> facets = counts.entrySet().stream()
                .map(entry -> new TagCount(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparingInt(TagCount::count).reversed().thenComparing(TagCount::tag))
                .limit(MAX_TAG_FACETS)
                .toList();

        int start = Math.min(Math.max(offset, 0), matches.size());
        int end = Math.min(start + Math.min(Math.max(limit, 1), MAX_PAGE_SIZE), matches.size());
        return new GalleryPage(List.copyOf(matches.subList(start, end)), matches.size(), facets);
    }

    /** One stored JPEG of a set; {@code number} starts at 1. */
    public byte[] image(String id, int number) {
        if (!ImageIdGenerator.ID_PATTERN.matcher(id).matches() || number < 1 || number > 99) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That picture was not found.");
        }
        return r2.get(id + "/" + JpegZipExporter.entryName(id, number - 1)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "That picture was not found."));
    }

    /** The number of pictures in a saved set, read from its record. */
    public int imageCount(String id) {
        if (!ImageIdGenerator.ID_PATTERN.matcher(id).matches()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved images were found for " + id + ".");
        }
        GalleryItem item = index.get(id);
        if (item == null) {
            item = toItem(readRecord(id));
        }
        if (item == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The saved record for " + id + " is unreadable.");
        }
        return item.imageCount();
    }

    private synchronized void refreshIndexIfStale() {
        Instant now = Instant.now();
        if (indexedAt != null && indexedAt.plus(INDEX_MAX_AGE).isAfter(now)) {
            return;
        }
        List<String> missing = r2.listFolders().stream()
                .filter(folder -> ImageIdGenerator.ID_PATTERN.matcher(folder).matches())
                .filter(folder -> !index.containsKey(folder))
                .toList();
        ExecutorService pool = Executors.newFixedThreadPool(INDEX_THREADS);
        try {
            List<Future<?>> loads = new ArrayList<>();
            for (String id : missing) {
                loads.add(pool.submit(() -> loadIntoIndex(id)));
            }
            for (Future<?> load : loads) {
                load.get();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Loading the gallery was interrupted.");
        } catch (ExecutionException exception) {
            // loadIntoIndex logs and skips its own failures, so this is unexpected.
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not load the gallery.", exception.getCause());
        } finally {
            pool.shutdownNow();
        }
        indexedAt = now;
    }

    private void loadIntoIndex(String id) {
        try {
            r2.get(id + "/record.json").ifPresent(bytes -> {
                try {
                    if (objectMapper.readTree(bytes) instanceof ObjectNode record) {
                        indexRecord(record);
                    }
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "Skipping unreadable record for " + id, exception);
                }
            });
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Could not read the record for " + id, exception);
        }
    }

    private void indexRecord(ObjectNode record) {
        GalleryItem item = toItem(record);
        if (item != null) {
            index.put(item.id(), item);
        }
    }

    /** Null when the record has no valid id or saved time. */
    static GalleryItem toItem(JsonNode record) {
        String id = record.path("id").asText("");
        if (!ImageIdGenerator.ID_PATTERN.matcher(id).matches()) {
            return null;
        }
        Instant createdAt;
        try {
            createdAt = Instant.parse(record.path("createdAt").asText(""));
        } catch (DateTimeParseException exception) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        record.path("tags").forEach(tag -> tags.add(tag.asText()));
        return new GalleryItem(id, createdAt, List.copyOf(tags), record.path("images").size());
    }

    private List<String> formatTags(String description) {
        JsonNode result = openAi.chatJson(
                chatModel,
                List.of(OpenAiClient.text(TAG_PROMPT.formatted(description))),
                Duration.ofSeconds(60),
                "tag formatting");
        return normalize(result.path("tags"));
    }

    /** Title-cases, trims and de-duplicates the model's tags and caps their number and length. */
    static List<String> normalize(JsonNode tags) {
        Set<String> unique = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        if (tags.isArray()) {
            for (JsonNode node : tags) {
                String tag = titleCase(node.asText("").trim());
                if (!tag.isEmpty() && tag.length() <= MAX_TAG_LENGTH && seen.add(tag.toLowerCase(Locale.ROOT))) {
                    unique.add(tag);
                }
                if (unique.size() == MAX_TAGS) {
                    break;
                }
            }
        }
        return List.copyOf(unique);
    }

    private static String titleCase(String text) {
        StringBuilder result = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT)).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.toString();
    }

    private ObjectNode readRecord(String id) {
        byte[] bytes = r2.get(id + "/record.json").orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "No saved images were found for " + id + "."));
        try {
            JsonNode record = objectMapper.readTree(bytes);
            if (record instanceof ObjectNode object) {
                return object;
            }
        } catch (IOException exception) {
            // Reported below.
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The saved record for " + id + " is unreadable.");
    }

    private void putRecord(String id, ObjectNode record) {
        try {
            r2.put(id + "/record.json", objectMapper.writeValueAsString(record).getBytes(StandardCharsets.UTF_8),
                    "application/json");
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Could not save the record for " + id + ".", exception);
        }
    }

    public static Map<String, Object> tagsResponse(String id, List<String> tags) {
        return Map.of("id", id, "tags", tags);
    }
}
