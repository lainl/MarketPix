package com.marketpix;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketpix.api.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PhotoSearchServiceTests {

    private static final ImageRecordService.GalleryItem CHAIR = new ImageRecordService.GalleryItem(
            "261340102026J2378", Instant.parse("2026-10-26T17:40:00Z"), List.of("Chair", "Red"), 3);

    private final OpenAiClient openAi = mock(OpenAiClient.class);
    private final ImageRecordService records = mock(ImageRecordService.class);
    private final PhotoSearchService search = new PhotoSearchService(openAi, records, "chat-model", "-04:00");

    PhotoSearchServiceTests() {
        when(records.isEnabled()).thenReturn(true);
        when(records.knownTags()).thenReturn(List.of("Chair", "Red", "Clock"));
    }

    @Test
    void anIdInTheMessageIsLookedUpWithoutAskingTheModel() {
        when(records.find("261340102026J2378")).thenReturn(Optional.of(CHAIR));

        PhotoSearchService.Result result = search.answer(List.of(user("can you pull up 261340102026j2378"))).orElseThrow();

        assertEquals(List.of(CHAIR), result.items());
        assertTrue(result.text().contains("Oct 26, 2026 at 13:40"), result.text());
        assertTrue(result.text().contains("tagged Chair, Red"), result.text());
        verify(openAi, never()).chatJson(anyString(), anyList(), any(), anyString());
    }

    @Test
    void anUnknownIdIsReported() {
        when(records.find(anyString())).thenReturn(Optional.empty());

        PhotoSearchService.Result result = search.answer(List.of(user("show 011200102026A0000"))).orElseThrow();

        assertTrue(result.items().isEmpty());
        assertTrue(result.text().contains("couldn't find a saved set with the id 011200102026A0000"), result.text());
    }

    @Test
    void tagsAndDatesComeFromTheModelButOnlyRealTagsAreSearched() throws Exception {
        when(openAi.chatJson(anyString(), anyList(), any(), anyString())).thenReturn(new ObjectMapper().readTree(
                "{\"search\":true,\"tags\":[\"chair\",\"Sofa\"],\"unknown\":[],\"from\":\"2026-10-20\",\"to\":\"2026-10-28\"}"));
        when(records.search(any(), any(), anyList(), anyInt(), anyInt()))
                .thenReturn(new ImageRecordService.GalleryPage(List.of(CHAIR), 1, List.of()));

        PhotoSearchService.Result result = search.answer(List.of(user("show me the chairs from last week"))).orElseThrow();

        verify(records).search(eq(LocalDate.of(2026, 10, 20)), eq(LocalDate.of(2026, 10, 28)), eq(List.of("Chair")),
                eq(0), anyInt());
        assertEquals(1, result.total());
        assertTrue(result.text().startsWith("I found 1 saved set tagged Chair from Oct 20, 2026 to Oct 28, 2026"), result.text());
        assertEquals("Gallery.html?from=2026-10-20&to=2026-10-28&tag=Chair", result.galleryLink());
    }

    @Test
    void aTagNobodyUsedIsExplainedInsteadOfSearched() throws Exception {
        when(openAi.chatJson(anyString(), anyList(), any(), anyString())).thenReturn(new ObjectMapper().readTree(
                "{\"search\":true,\"tags\":[],\"unknown\":[\"sofa\"],\"from\":null,\"to\":null}"));

        PhotoSearchService.Result result = search.answer(List.of(user("find the sofa photos"))).orElseThrow();

        assertTrue(result.text().startsWith("No saved sets are tagged sofa"), result.text());
        verify(records, never()).search(any(), any(), anyList(), anyInt(), anyInt());
    }

    @Test
    void ordinaryChatIsLeftToTheAssistant() throws Exception {
        when(openAi.chatJson(anyString(), anyList(), any(), anyString())).thenReturn(new ObjectMapper().readTree(
                "{\"search\":false,\"tags\":[],\"unknown\":[],\"from\":null,\"to\":null}"));

        assertFalse(search.answer(List.of(user("the item is the brass clock, not the box"))).isPresent());
        // No photo words at all: the model is not even asked.
        assertFalse(search.answer(List.of(user("thanks!"))).isPresent());
        verify(openAi, times(1)).chatJson(anyString(), anyList(), any(), anyString());
    }

    @Test
    void nothingIsSearchedWhenCloudStorageIsOff() {
        when(records.isEnabled()).thenReturn(false);

        assertTrue(search.answer(List.of(user("show 261340102026J2378"))).isEmpty());
    }

    private static ChatMessage user(String content) {
        return new ChatMessage("user", content);
    }
}
