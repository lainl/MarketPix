package com.marketpix;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

    @Test
    void namesDownloadsAfterTheItemCodeWhenThereIsOne() {
        assertEquals("Lot-8900", JpegZipExporter.downloadName(" Lot-8900 ", "261340102026J2378"));
        assertEquals("A_12_B", JpegZipExporter.downloadName("A/12 B", "261340102026J2378"));
        assertEquals("261340102026J2378", JpegZipExporter.downloadName("", "261340102026J2378"));
        assertEquals("261340102026J2378", JpegZipExporter.downloadName("../", "261340102026J2378"));
        assertEquals("261340102026J2378", JpegZipExporter.downloadName(null, "261340102026J2378"));
        assertEquals("Lot-8900_02.jpg", JpegZipExporter.entryName("Lot-8900", 1));
    }

    @Test
    void deletesOnlySetsOlderThanThreeMonths() {
        R2Client r2 = mock(R2Client.class);
        when(r2.isConfigured()).thenReturn(true);
        when(r2.listFolders()).thenReturn(List.of("101010102026A0001", "202020102026A0002"));
        Instant old = Instant.now().minus(100, ChronoUnit.DAYS);
        Instant recent = Instant.now().minus(10, ChronoUnit.DAYS);
        when(r2.get("101010102026A0001/record.json")).thenReturn(Optional.of(record("101010102026A0001", old)));
        when(r2.get("202020102026A0002/record.json")).thenReturn(Optional.of(record("202020102026A0002", recent)));
        ImageRecordService service = new ImageRecordService(r2, null, new ObjectMapper(), "", "UTC");

        service.deleteExpiredSets();

        verify(r2).delete("101010102026A0001/record.json");
        verify(r2).delete("101010102026A0001/101010102026A0001_01.jpg");
        verify(r2, never()).delete("202020102026A0002/record.json");
    }

    @Test
    void addsPicturesToTheSetThatAlreadyHasTheItemCode(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder)
            throws Exception {
        R2Client r2 = mock(R2Client.class);
        when(r2.isConfigured()).thenReturn(true);
        when(r2.listFolders()).thenReturn(List.of("101010102026A0001"));
        byte[] existing = ("{\"id\":\"101010102026A0001\",\"createdAt\":\"2026-10-30T18:04:00Z\",\"tags\":[\"Clock\"],"
                + "\"objectId\":\"410\",\"images\":[\"101010102026A0001/101010102026A0001_01.jpg\"]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(r2.get("101010102026A0001/record.json")).thenReturn(Optional.of(existing));
        java.nio.file.Path jpeg = java.nio.file.Files.write(folder.resolve("new.jpg"), new byte[] {1, 2, 3});
        ImageRecordService service = new ImageRecordService(r2, null, new ObjectMapper(), "", "UTC");

        ImageRecordService.StoredSet set = service.store("202020102026A0002", List.of(jpeg, jpeg), " 410 ");

        assertEquals(new ImageRecordService.StoredSet("101010102026A0001", 2), set);
        verify(r2).put(org.mockito.ArgumentMatchers.eq("101010102026A0001/101010102026A0001_02.jpg"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("image/jpeg"));
        verify(r2).put(org.mockito.ArgumentMatchers.eq("101010102026A0001/101010102026A0001_03.jpg"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("image/jpeg"));
        verify(r2, never()).put(org.mockito.ArgumentMatchers.eq("202020102026A0002/record.json"),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        ImageRecordService.GalleryItem merged = service.find("101010102026A0001").orElseThrow();
        assertEquals(3, merged.imageCount());
        assertEquals(List.of("Clock"), merged.tags());
    }

    @Test
    void deletingOnePictureMovesTheRestUpANumber() {
        R2Client r2 = mock(R2Client.class);
        when(r2.isConfigured()).thenReturn(true);
        String id = "101010102026A0001";
        byte[] record = ("{\"id\":\"" + id + "\",\"createdAt\":\"2026-10-30T18:04:00Z\",\"tags\":[],\"images\":[\""
                + id + "/" + id + "_01.jpg\",\"" + id + "/" + id + "_02.jpg\",\"" + id + "/" + id + "_03.jpg\"]}")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(r2.get(id + "/record.json")).thenReturn(Optional.of(record));
        when(r2.get(id + "/" + id + "_03.jpg")).thenReturn(Optional.of(new byte[] {3}));
        ImageRecordService service = new ImageRecordService(r2, null, new ObjectMapper(), "", "UTC");

        ImageRecordService.GalleryItem item = service.deleteImage(id, 2).orElseThrow();

        verify(r2).put(id + "/" + id + "_02.jpg", new byte[] {3}, "image/jpeg");
        verify(r2).delete(id + "/" + id + "_03.jpg");
        verify(r2, never()).delete(id + "/record.json");
        assertEquals(2, item.imageCount());
        assertEquals(1, item.revision());
    }

    @Test
    void deletingTheOnlyPictureDeletesTheSet() {
        R2Client r2 = mock(R2Client.class);
        when(r2.isConfigured()).thenReturn(true);
        Instant now = Instant.now();
        when(r2.get("101010102026A0001/record.json")).thenReturn(Optional.of(record("101010102026A0001", now)));
        ImageRecordService service = new ImageRecordService(r2, null, new ObjectMapper(), "", "UTC");

        assertEquals(Optional.empty(), service.deleteImage("101010102026A0001", 1));
        verify(r2).delete("101010102026A0001/record.json");
        verify(r2).delete("101010102026A0001/101010102026A0001_01.jpg");
    }

    private static byte[] record(String id, Instant createdAt) {
        return ("{\"id\":\"" + id + "\",\"createdAt\":\"" + createdAt + "\",\"tags\":[],\"images\":[\"" + id + "/" + id
                + "_01.jpg\"]}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static ImageRecordService.GalleryItem item(String id, String createdAt, String... tags) {
        return new ImageRecordService.GalleryItem(id, Instant.parse(createdAt), List.of(tags), 1);
    }
}
