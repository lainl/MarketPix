package com.marketpix;

import com.fasterxml.jackson.databind.JsonNode;
import com.marketpix.api.ChatMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Answers chat requests for saved photos ("show me 261340102026J2378", "the red chairs from last week").
 * Ids are read straight from the message; only tags that really exist on saved sets and a date range
 * are worked out by the chat model. The search itself is the gallery's own, so the chat can only find
 * what the Gallery page would.
 */
@Service
public class PhotoSearchService {

    private static final Logger LOGGER = Logger.getLogger(PhotoSearchService.class.getName());
    private static final int MAX_RESULTS = 12;
    private static final int CONTEXT_MESSAGES = 6;
    private static final Pattern ID_IN_TEXT = Pattern.compile("\\b\\d{12}[A-Za-z]\\d{4}\\b");
    // Only messages that could be about saved photos are sent to the model, so ordinary chat costs nothing extra.
    private static final Pattern MAYBE_PHOTO_REQUEST = Pattern.compile(
            "\\b(photo|picture|pic|image|set|show|find|fetch|get|search|look|pull|bring|retrieve|tag|saved|gallery|"
                    + "upload|item|id)s?\\b", Pattern.CASE_INSENSITIVE);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SAVED_AT = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm", Locale.ENGLISH);
    private static final String PROMPT = """
            You read an auctioneer's chat with MarketPix and decide whether their LAST message asks to find, show,
            fetch or retrieve photos that were already saved. Saved photo sets can only be found by tag and by the
            day they were saved. Reply with JSON only:
            {"search": true or false, "tags": ["..."], "unknown": ["..."], "from": "YYYY-MM-DD" or null, "to": "YYYY-MM-DD" or null}
            - "search" is false for anything else, such as describing an item for a new image job. It is also
              false when the user asks for photos by an item or lot code (such as "item 8900"), since saved sets
              do not record item codes.
            - "tags": only tags copied exactly from the list of existing tags below that the user asked for
              (match singular and plural, "red chairs" -> "Red", "Chair"). Every tag must be on a set for it to match,
              so do not add tags the user did not ask for.
            - "unknown": words the user asked to filter by that match no existing tag.
            - "from"/"to": the inclusive days asked for, using today = %s. "yesterday", "last week", "in October"
              and plain dates become a range; null when no time was mentioned.
            Existing tags: %s
            Conversation (oldest first):
            %s
            """;

    private final OpenAiClient openAi;
    private final ImageRecordService records;
    private final String model;
    private final ZoneId zone;

    public PhotoSearchService(
            OpenAiClient openAi,
            ImageRecordService records,
            @Value("${openai.chat.model}") String model,
            @Value("${marketpix.id.timezone}") String timezone) {
        this.openAi = openAi;
        this.records = records;
        this.model = model;
        this.zone = timezone == null || timezone.isBlank() ? ZoneId.systemDefault() : ZoneId.of(timezone.trim());
    }

    /** The answer when the last message asks for saved photos; empty when the chat should answer normally. */
    public Optional<Result> answer(List<ChatMessage> messages) {
        if (!records.isEnabled() || messages.isEmpty()) {
            return Optional.empty();
        }
        ChatMessage last = messages.get(messages.size() - 1);
        if (!"user".equals(last.role())) {
            return Optional.empty();
        }
        try {
            List<String> ids = idsIn(last.content());
            if (!ids.isEmpty()) {
                return Optional.of(byIds(ids));
            }
            if (!MAYBE_PHOTO_REQUEST.matcher(last.content()).find()) {
                return Optional.empty();
            }
            return interpret(messages).map(this::byTagsAndDates);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "The photo search from chat failed; answering as normal chat", exception);
            return Optional.empty();
        }
    }

    static List<String> idsIn(String text) {
        Set<String> ids = new LinkedHashSet<>();
        Matcher matcher = ID_IN_TEXT.matcher(text);
        while (matcher.find()) {
            ids.add(matcher.group().toUpperCase(Locale.ROOT));
        }
        return List.copyOf(ids);
    }

    private Result byIds(List<String> ids) {
        List<ImageRecordService.GalleryItem> found = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String id : ids) {
            records.find(id).ifPresentOrElse(found::add, () -> missing.add(id));
        }
        StringBuilder text = new StringBuilder();
        for (ImageRecordService.GalleryItem item : found) {
            text.append(text.length() == 0 ? "" : " ")
                    .append("Here is ").append(item.id()).append(", saved ")
                    .append(SAVED_AT.format(item.createdAt().atZone(zone))).append(" with ")
                    .append(photos(item.imageCount()))
                    .append(item.tags().isEmpty() ? " and no tags." : ", tagged " + String.join(", ", item.tags()) + ".");
        }
        if (!missing.isEmpty()) {
            text.append(text.length() == 0 ? "" : " ")
                    .append("I couldn't find a saved set with the id ").append(String.join(", ", missing))
                    .append(". Check the id in the Gallery.");
        }
        LOGGER.info("Chat looked up " + ids.size() + " id(s): " + found.size() + " found.");
        return new Result(text.toString(), List.copyOf(found), found.size(), null);
    }

    private Optional<Query> interpret(List<ChatMessage> messages) {
        StringBuilder conversation = new StringBuilder();
        for (ChatMessage message : messages.subList(Math.max(0, messages.size() - CONTEXT_MESSAGES), messages.size())) {
            String content = message.content().length() > 1000 ? message.content().substring(0, 1000) : message.content();
            conversation.append("user".equals(message.role()) ? "User: " : "MarketPix: ").append(content).append('\n');
        }
        List<String> known = records.knownTags();
        JsonNode reply = openAi.chatJson(model,
                List.of(OpenAiClient.text(PROMPT.formatted(LocalDate.now(zone), known.isEmpty() ? "(none yet)" : String.join(", ", known), conversation))),
                Duration.ofSeconds(30), "photo search");
        if (!reply.path("search").asBoolean(false)) {
            return Optional.empty();
        }
        Map<String, String> knownByLowerCase = new HashMap<>();
        known.forEach(tag -> knownByLowerCase.put(tag.toLowerCase(Locale.ROOT), tag));
        List<String> tags = new ArrayList<>();
        reply.path("tags").forEach(tag -> {
            String match = knownByLowerCase.get(tag.asText("").trim().toLowerCase(Locale.ROOT));
            if (match != null && !tags.contains(match)) {
                tags.add(match);
            }
        });
        List<String> unknown = new ArrayList<>();
        reply.path("unknown").forEach(word -> {
            String value = word.asText("").trim();
            if (!value.isEmpty()) {
                unknown.add(value);
            }
        });
        LocalDate from = day(reply.path("from"));
        LocalDate to = day(reply.path("to"));
        if (from != null && to != null && from.isAfter(to)) {
            LocalDate swap = from;
            from = to;
            to = swap;
        }
        return Optional.of(new Query(List.copyOf(tags), List.copyOf(unknown), from, to));
    }

    private Result byTagsAndDates(Query query) {
        String filter = describe(query);
        if (!query.unknown().isEmpty() && query.tags().isEmpty()) {
            LOGGER.info("Chat photo search found no tag matching " + query.unknown());
            return new Result("No saved sets are tagged " + String.join(", ", query.unknown())
                    + " yet. Try another word, or browse the Gallery.", List.of(), 0, null);
        }
        ImageRecordService.GalleryPage page = records.search(query.from(), query.to(), query.tags(), 0, MAX_RESULTS);
        LOGGER.info("Chat photo search" + filter + ": " + page.total() + " set(s) found.");
        String galleryLink = galleryLink(query);
        if (page.total() == 0) {
            return new Result("I couldn't find any saved sets" + filter + "."
                    + (query.tags().size() > 1 ? " Every tag must be on a set, so try fewer tags." : ""), List.of(), 0, galleryLink);
        }
        StringBuilder text = new StringBuilder("I found ").append(page.total())
                .append(page.total() == 1 ? " saved set" : " saved sets").append(filter).append('.');
        if (page.total() > page.items().size()) {
            text.append(" Here are the newest ").append(page.items().size()).append("; the Gallery shows them all.");
        }
        if (!query.unknown().isEmpty()) {
            text.append(" No saved set is tagged ").append(String.join(", ", query.unknown())).append(", so I left that out.");
        }
        return new Result(text.toString(), page.items(), page.total(), galleryLink);
    }

    private String describe(Query query) {
        StringBuilder filter = new StringBuilder();
        if (!query.tags().isEmpty()) {
            filter.append(" tagged ").append(String.join(" and ", query.tags()));
        }
        if (query.from() != null && query.to() != null) {
            filter.append(query.from().equals(query.to())
                    ? " from " + DAY.format(query.from())
                    : " from " + DAY.format(query.from()) + " to " + DAY.format(query.to()));
        } else if (query.from() != null) {
            filter.append(" since ").append(DAY.format(query.from()));
        } else if (query.to() != null) {
            filter.append(" up to ").append(DAY.format(query.to()));
        }
        return filter.toString();
    }

    /** The Gallery page with the same filters, for "see all". */
    private static String galleryLink(Query query) {
        List<String> parameters = new ArrayList<>();
        if (query.from() != null) {
            parameters.add("from=" + query.from());
        }
        if (query.to() != null) {
            parameters.add("to=" + query.to());
        }
        query.tags().forEach(tag -> parameters.add("tag=" + URLEncoder.encode(tag, StandardCharsets.UTF_8)));
        return "Gallery.html" + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
    }

    private static LocalDate day(JsonNode node) {
        if (node.isNull() || node.isMissingNode()) {
            return null;
        }
        try {
            return LocalDate.parse(node.asText().trim());
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private static String photos(int count) {
        return count + (count == 1 ? " photo" : " photos");
    }

    private record Query(List<String> tags, List<String> unknown, LocalDate from, LocalDate to) {
    }

    /** What the chat says, the sets to show (newest first, at most twelve), how many matched, and a Gallery link. */
    public record Result(String text, List<ImageRecordService.GalleryItem> items, int total, String galleryLink) {
    }
}
