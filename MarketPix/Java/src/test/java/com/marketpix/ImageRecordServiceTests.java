package com.marketpix;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ImageRecordServiceTests {

    private static final ZoneId AMSTERDAM = ZoneId.of("Europe/Amsterdam");
    private static final ImageRecordService.GalleryItem RED_CHAIR = item(
            "261340102026J2378", "2026-10-26T12:40:00Z", "Chair", "Red", "American");
    private static final ImageRecordService.GalleryItem OAK_CHAIR = item(
            "270915102026B0001", "2026-10-27T08:15:00Z", "Chair", "Oak");
    private static final ImageRecordService.GalleryItem VASE = item(
            "012359112026Q9999", "2026-10-31T23:30:00Z", "Vase");
    private static final List<ImageRecordService.GalleryItem> ALL = List.of(RED_CHAIR, VASE, OAK_CHAIR);

    @Test
    void listsNewestFirstWithTagCounts() {
        ImageRecordService.GalleryPage page = ImageRecordService.filter(ALL, AMSTERDAM, null, null, null, 0, 48);

        assertEquals(List.of(VASE, OAK_CHAIR, RED_CHAIR), page.items());
        assertEquals(3, page.total());
        assertEquals(new ImageRecordService.TagCount("Chair", 2), page.tags().get(0));
    }

    @Test
    void requiresEveryTagIgnoringCase() {
        ImageRecordService.GalleryPage page = ImageRecordService.filter(
                ALL, AMSTERDAM, null, null, List.of("chair", " RED "), 0, 48);

        assertEquals(List.of(RED_CHAIR), page.items());
    }

    @Test
    void findsASetByItsObjectIdWithoutItBeingATag() {
        ImageRecordService.GalleryItem lamp = new ImageRecordService.GalleryItem(
                "101010102026A0001", Instant.parse("2026-10-28T10:00:00Z"), List.of("Lamp"), 1, "Lot-8900");
        ImageRecordService.GalleryPage page = ImageRecordService.filter(
                List.of(RED_CHAIR, lamp), AMSTERDAM, null, null, List.of("lot-8900"), 0, 48);

        assertEquals(List.of(lamp), page.items());
        assertEquals(List.of(new ImageRecordService.TagCount("Lamp", 1)), page.tags());
    }

    @Test
    void datesAreInclusiveDaysInTheIdTimeZone() {
        // 23:30 UTC on 31 October is already 1 November in Amsterdam.
        assertEquals(List.of(VASE), ImageRecordService.filter(
                ALL, AMSTERDAM, LocalDate.of(2026, 11, 1), null, null, 0, 48).items());
        assertEquals(List.of(OAK_CHAIR, RED_CHAIR), ImageRecordService.filter(
                ALL, AMSTERDAM, LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 27), null, 0, 48).items());
        assertEquals(List.of(), ImageRecordService.filter(
                ALL, AMSTERDAM, LocalDate.of(2026, 10, 28), LocalDate.of(2026, 10, 31), null, 0, 48).items());
    }

    @Test
    void pagesThroughMatches() {
        ImageRecordService.GalleryPage page = ImageRecordService.filter(ALL, AMSTERDAM, null, null, null, 1, 1);

        assertEquals(List.of(OAK_CHAIR), page.items());
        assertEquals(3, page.total());
        assertEquals(List.of(), ImageRecordService.filter(ALL, AMSTERDAM, null, null, null, 10, 5).items());
    }

    @Test
    void readsGalleryItemsFromStoredRecords() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ImageRecordService.GalleryItem item = ImageRecordService.toItem(mapper.readTree("""
                {"id":"261340102026J2378","createdAt":"2026-10-26T12:40:00Z","tags":["Chair"],
                 "images":["261340102026J2378/261340102026J2378_01.jpg","261340102026J2378/261340102026J2378_02.jpg"]}
                """));

        assertEquals(new ImageRecordService.GalleryItem(
                "261340102026J2378", Instant.parse("2026-10-26T12:40:00Z"), List.of("Chair"), 2), item);
        assertEquals("Lot-8900", ImageRecordService.toItem(mapper.readTree("""
                {"id":"261340102026J2378","createdAt":"2026-10-26T12:40:00Z","tags":[],"objectId":"Lot-8900"}
                """)).objectId());
        assertNull(ImageRecordService.toItem(mapper.readTree("{\"id\":\"nope\",\"createdAt\":\"2026-10-26T12:40:00Z\"}")));
        assertNull(ImageRecordService.toItem(mapper.readTree("{\"id\":\"261340102026J2378\"}")));
    }

    private static ImageRecordService.GalleryItem item(String id, String createdAt, String... tags) {
        return new ImageRecordService.GalleryItem(id, Instant.parse(createdAt), List.of(tags), 1);
    }
}
