package com.marketpix;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
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
import java.time.Period;
import java.time.ZonedDateTime;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    private static final int MAX_KNOWN_TAGS = 300;
    private static final int INDEX_THREADS = 8;
    private static final Duration INDEX_MAX_AGE = Duration.ofMinutes(3);
    private static final Period RETENTION = Period.ofMonths(3);
    private static final int MAX_SET_IMAGES = 99;
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
            @Value("${openai.chat.model}") String chatModel,
            @Value("${marketpix.id.timezone}") String timezone) {
        this.r2 = r2;
        this.openAi = openAi;
        this.objectMapper = objectMapper;
        this.chatModel = chatModel;
        this.zone = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
    }

    /**
     * One saved set as the gallery shows it. {@code createdAt} is when pictures were last saved to it, so a set
     * that gets more pictures moves to the top and is kept three months from then. {@code objectId} is the
     * item code the user typed in, or empty; the gallery shows it as the title and downloads are named after it.
     */
    public record GalleryItem(String id, Instant createdAt, List<String> tags, int imageCount, String objectId) {
        public GalleryItem(String id, Instant createdAt, List<String> tags, int imageCount) {
            this(id, createdAt, tags, imageCount, "");
        }
    }

    /** Where pictures were saved: the set's id and the number of the first new picture (1 for a new set). */
    public record StoredSet(String id, int firstNumber) {
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
    public StoredSet store(String id, List<Path> jpegs) {
        return store(id, jpegs, null);
    }

    /**
     * As {@link #store(String, List)}, with the item code the user typed in. It is saved as its own field, not
     * a tag, and a gallery search for it still finds the set. When a saved set already has this item code, the
     * pictures are added to that set instead and {@code id} is not used.
     */
    public synchronized StoredSet store(String id, List<Path> jpegs, String objectId) {
        String cleanObjectId = cleanObjectId(objectId);
        Optional<GalleryItem> existing = setWithObjectId(cleanObjectId);
        if (existing.isPresent() && existing.get().imageCount() + jpegs.size() <= MAX_SET_IMAGES) {
            return append(existing.get().id(), jpegs);
        }
        List<String> keys = upload(id, 0, jpegs);
        ObjectNode record = objectMapper.createObjectNode();
        record.put("id", id);
        record.put("createdAt", Instant.now().toString());
        record.putArray("tags");
        if (!cleanObjectId.isEmpty()) {
            record.put("objectId", cleanObjectId);
        }
        ArrayNode images = record.putArray("images");
        keys.forEach(images::add);
        putRecord(id, record);
        indexRecord(record);
        return new StoredSet(id, 1);
    }

    /** The oldest saved set with this item code (ignoring case), if any. */
    private Optional<GalleryItem> setWithObjectId(String objectId) {
        if (objectId.isEmpty()) {
            return Optional.empty();
        }
        try {
            refreshIndexIfStale();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Could not look for a saved set with item code " + objectId
                    + "; saving the pictures as a new set", exception);
            return Optional.empty();
        }
        return index.values().stream()
                .filter(item -> item.objectId().equalsIgnoreCase(objectId))
                .min(Comparator.comparing(GalleryItem::createdAt).thenComparing(GalleryItem::id));
    }

    /** Adds pictures to the end of a saved set, numbered on from its last one. */
    private StoredSet append(String id, List<Path> jpegs) {
        ObjectNode record = readRecord(id);
        int start = record.path("images").size();
        List<String> keys = upload(id, start, jpegs);
        ArrayNode images = record.withArray("images");
        keys.forEach(images::add);
        record.put("updatedAt", Instant.now().toString());
        putRecord(id, record);
        indexRecord(record);
        return new StoredSet(id, start + 1);
    }

    /** Uploads JPEGs as pictures {@code start + 1, start + 2, ...} of a set and returns their keys. */
    private List<String> upload(String id, int start, List<Path> jpegs) {
        List<String> keys = new ArrayList<>();
        for (int index = 0; index < jpegs.size(); index++) {
            String key = id + "/" + JpegZipExporter.entryName(id, start + index);
            try {
                r2.put(key, Files.readAllBytes(jpegs.get(index)), "image/jpeg");
            } catch (IOException exception) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR, "Could not read a finished picture for storage.", exception);
            }
            keys.add(key);
        }
        return keys;
    }

    /**
     * Turns the user's free-text description into tags and adds them to the set's tags, so a set that got
     * more pictures keeps the tags from earlier descriptions. Returns every tag on the set, or an empty list
     * when the description held none (nothing is saved then).
     */
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
        List<String> newTags = formatTags(description.trim());
        if (newTags.isEmpty()) {
            return newTags;
        }
        List<String> tags = new ArrayList<>();
        record.path("tags").forEach(tag -> tags.add(tag.asText()));
        for (String tag : newTags) {
            if (tags.size() < MAX_TAGS && tags.stream().noneMatch(known -> known.equalsIgnoreCase(tag))) {
                tags.add(tag);
            }
        }
        ArrayNode array = record.putArray("tags");
        tags.forEach(array::add);
        putRecord(id, record);
        indexRecord(record);
        return List.copyOf(tags);
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
            // The object id is not a tag, but searching for it in the tag box should still find the set.
            if (!item.objectId().isEmpty()) {
                itemTags.add(item.objectId().toLowerCase(Locale.ROOT));
            }
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

    /** The saved set with exactly this id, if there is one. */
    public Optional<GalleryItem> find(String id) {
        if (id == null || !ImageIdGenerator.ID_PATTERN.matcher(id).matches() || !r2.isConfigured()) {
            return Optional.empty();
        }
        refreshIndexIfStale();
        if (!index.containsKey(id)) {
            loadIntoIndex(id);
        }
        return Optional.ofNullable(index.get(id));
    }

    /** Every tag on a saved set, most used first, so a search can be matched against real tags. */
    public List<String> knownTags() {
        if (!r2.isConfigured()) {
            return List.of();
        }
        refreshIndexIfStale();
        Map<String, Integer> counts = new HashMap<>();
        for (GalleryItem item : index.values()) {
            item.tags().forEach(tag -> counts.merge(tag, 1, Integer::sum));
        }
        return counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
                .limit(MAX_KNOWN_TAGS)
                .map(Map.Entry::getKey)
                .toList();
    }

    /** The name a set downloads under: its item code when it has one, otherwise its id. */
    public String downloadName(String id) {
        return find(id).map(item -> JpegZipExporter.downloadName(item.objectId(), id)).orElse(id);
    }

    /**
     * Saved sets are kept for three months. This runs ten minutes after start-up (deploys restart the
     * server) and then once a day, so it costs one pass over the gallery index per day.
     */
    @Scheduled(initialDelay = 10, fixedDelay = 24 * 60, timeUnit = TimeUnit.MINUTES)
    public void deleteExpiredSets() {
        if (!r2.isConfigured()) {
            return;
        }
        try {
            refreshIndexIfStale();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Skipping the expired-set cleanup: the gallery could not be loaded", exception);
            return;
        }
        Instant cutoff = ZonedDateTime.now(zone).minus(RETENTION).toInstant();
        int deleted = 0;
        for (GalleryItem item : List.copyOf(index.values())) {
            if (!item.createdAt().isBefore(cutoff)) {
                continue;
            }
            try {
                delete(item.id());
                deleted++;
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "Could not delete expired set " + item.id(), exception);
            }
        }
        if (deleted > 0) {
            LOGGER.info("Deleted " + deleted + " saved set(s) older than three months.");
        }
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

    /**
     * Deletes a saved set from storage. The record goes first, so a set that is only partly deleted no
     * longer shows in the gallery.
     */
    public void delete(String id) {
        int count = imageCount(id);
        r2.delete(id + "/record.json");
        index.remove(id);
        for (int index = 0; index < count; index++) {
            r2.delete(id + "/" + JpegZipExporter.entryName(id, index));
        }
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
            // A set that got more pictures counts from then on.
            createdAt = Instant.parse(record.hasNonNull("updatedAt")
                    ? record.path("updatedAt").asText()
                    : record.path("createdAt").asText(""));
        } catch (DateTimeParseException exception) {
            return null;
        }
        List<String> tags = new ArrayList<>();
        record.path("tags").forEach(tag -> tags.add(tag.asText()));
        return new GalleryItem(id, createdAt, List.copyOf(tags), record.path("images").size(),
                record.path("objectId").asText(""));
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

    /** The object id as it is saved: trimmed and kept as typed; empty when blank or too long. */
    static String cleanObjectId(String objectId) {
        if (objectId == null) {
            return "";
        }
        String trimmed = objectId.trim();
        return trimmed.length() <= MAX_TAG_LENGTH ? trimmed : "";
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
