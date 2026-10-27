package com.marketpix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BulkJobServiceTests {

    private static final String LINK = "https://www.dropbox.com/scl/fo/abc123/lot-photos?rlkey=xyz&dl=0";

    @TempDir
    Path tempDirectory;

    @Test
    void groupsPhotosByItemCodeAndEstimatesTheCost() {
        DropboxClient dropbox = dropbox(List.of(
                file("8900_01.jpg"), file("8900_02.webp"), file("10000_01.png"), file("8900_03.heic"), file("notes.txt")));
        BulkJobService service = service(dropbox, analyzer(), placement());

        BulkJobView view = service.create(LINK);

        int heic = ImageMagickConverter.isAvailable() ? 1 : 0;
        assertEquals("AWAITING_CONFIRMATION", view.status());
        assertEquals(3 + heic, view.total());
        assertEquals(2, view.items());
        assertEquals(1 - heic, view.skipped());
        assertEquals(0.25 * (3 + heic) + 0.02 * 2, view.estimatedCost(), 0.0001);
        assertTrue(view.message().contains("Found " + (3 + heic) + " photos of 2 items in \"Lot photos\""));
        verify(dropbox, never()).downloadSharedFile(anyString(), anyString());
    }

    @Test
    void identifiesEachItemOnceAndKeepsGoingWhenAPhotoFails() throws Exception {
        DropboxClient dropbox = dropbox(List.of(
                file("10000_01.jpg"), file("10000_02.bmp"), file("10000_02.png"),
                file("8900_01.jpg"), file("8900_02.jpg"), file("8900_03.png")));
        when(dropbox.downloadSharedFile(eq(LINK), anyString())).thenAnswer(invocation ->
                encoded(invocation.getArgument(1, String.class).endsWith(".bmp") ? "bmp" : "png"));
        when(dropbox.downloadSharedFile(LINK, "8900_02.jpg")).thenThrow(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "Dropbox is rate limiting MarketPix."));
        ImageAnalysisService analyzer = analyzer();
        ImagePlacementService placement = placement();
        BulkJobService service = service(dropbox, analyzer, placement);
        UUID jobId = UUID.fromString(service.create(LINK).jobId());

        service.start(jobId);
        BulkJobView done = awaitFinished(service, jobId);

        assertEquals("COMPLETE", done.status());
        assertEquals(6, done.finished());
        assertEquals(1, done.failed());
        assertEquals(List.of("8900_02.jpg: Dropbox is rate limiting MarketPix."), done.failures());
        assertEquals(List.of("Item 10000: the photos may not all show the same object; check them before listing."),
                done.warnings());
        assertEquals("Lot_photos_edited.zip", done.downloadFilename());
        assertTrue(done.message().contains("5 of 6 photos"));
        verify(analyzer, times(2)).analyze(anyList(), anyString());
        verify(placement, times(2)).place(any(), any(), eq("toy truck"), any());
        verify(placement, times(3)).place(any(), any(), eq("brass clock"), any());

        List<String> entries = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(service.getDownload(jobId)))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.add(entry.getName());
            }
        }
        assertEquals(List.of("8900_01.jpg", "8900_03.jpg", "10000_01.jpg", "10000_02.jpg", "10000_02 (2).jpg"), entries);
    }

    @Test
    void reportsPhotosThatCannotBeRead() throws Exception {
        DropboxClient dropbox = dropbox(List.of(file("8900_01.jpg"), file("8900_02.jpg")));
        when(dropbox.downloadSharedFile(LINK, "8900_01.jpg")).thenAnswer(invocation -> encoded("png"));
        when(dropbox.downloadSharedFile(LINK, "8900_02.jpg")).thenReturn(new byte[]{1, 2, 3, 4});
        BulkJobService service = service(dropbox, analyzer(), placement());
        UUID jobId = UUID.fromString(service.create(LINK).jobId());

        service.start(jobId);
        BulkJobView done = awaitFinished(service, jobId);

        assertEquals("COMPLETE", done.status());
        assertEquals(1, done.failures().size());
        assertTrue(done.failures().get(0).startsWith("8900_02.jpg: "), done.failures().get(0));
    }

    @Test
    void placesEveryPhotoIntoTheChosenBackground() throws Exception {
        DropboxClient dropbox = dropbox(List.of(file("8900_01.jpg"), file("8900_02.jpg")));
        when(dropbox.downloadSharedFile(eq(LINK), anyString())).thenAnswer(invocation -> encoded("png"));
        ImagePlacementService placement = placement();
        BulkJobService service = service(dropbox, analyzer(), placement);
        MockMultipartFile background = new MockMultipartFile(
                "background", "table.png", "image/png", encoded("png"));
        BulkJobView created = service.create(LINK, background);
        UUID jobId = UUID.fromString(created.jobId());

        service.start(jobId);
        BulkJobView done = awaitFinished(service, jobId);

        assertTrue(created.backgroundScene());
        assertEquals("COMPLETE", done.status());
        assertTrue(done.message().contains("into your background scene"), done.message());
        verify(placement, times(2)).place(any(), notNull(), eq("toy truck"), any());
    }

    @Test
    void rejectsABackgroundThatIsNotAnImage() {
        BulkJobService service = service(dropbox(List.of(file("8900_01.jpg"))), analyzer(), placement());
        MockMultipartFile notes = new MockMultipartFile("background", "notes.txt", "text/plain", new byte[]{1, 2, 3});

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class, () -> service.create(LINK, notes));

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
        assertTrue(rejected.getReason().contains("background"));
    }

    @Test
    void cancellingBeforeStartingRemovesTheJob() {
        BulkJobService service = service(dropbox(List.of(file("one.jpg"))), analyzer(), placement());
        UUID jobId = UUID.fromString(service.create(LINK).jobId());

        assertEquals("CANCELLED", service.cancel(jobId).status());

        ResponseStatusException missing = assertThrows(ResponseStatusException.class, () -> service.status(jobId));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatusCode());
    }

    @Test
    void rejectsLinksThatAreNotDropbox() {
        BulkJobService service = service(dropbox(List.of()), analyzer(), placement());

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class,
                () -> service.create("https://example.com/scl/fo/abc"));

        assertEquals(HttpStatus.BAD_REQUEST, rejected.getStatusCode());
    }

    @Test
    void reportsAFolderWithoutPhotos() {
        BulkJobService service = service(dropbox(List.of(file("notes.txt"))), analyzer(), placement());

        ResponseStatusException rejected = assertThrows(ResponseStatusException.class, () -> service.create(LINK));

        assertTrue(rejected.getReason().contains("No photos were found"));
    }

    private BulkJobView awaitFinished(BulkJobService service, UUID jobId) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(20));
        BulkJobView view = service.status(jobId);
        while (List.of("PROCESSING", "EXPORTING", "CANCELLING").contains(view.status())) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("The bulk job did not finish: " + view);
            }
            Thread.sleep(20);
            view = service.status(jobId);
        }
        return view;
    }

    private static ImageRecordService disabledRecords() {
        return mock(ImageRecordService.class);
    }

    private BulkJobService service(
            DropboxClient dropbox, ImageAnalysisService analyzer, ImagePlacementService placement) {
        return new BulkJobService(tempDirectory, dropbox, analyzer, new ListingPhotoEditor(analyzer, placement),
                new JpegZipExporter(), mock(ImageIdGenerator.class), disabledRecords(), 0.25, 0.02, 500);
    }

    /** Item 8900 is a toy truck; item 10000's photos look like different objects. */
    private ImageAnalysisService analyzer() {
        ImageAnalysisService analyzer = mock(ImageAnalysisService.class);
        when(analyzer.verifyDetails(any(), any(), any()))
                .thenReturn(new ImageAnalysisService.DetailCheck(false, false, false, false, ""));
        when(analyzer.analyze(anyList(), anyString())).thenAnswer(invocation -> {
            List<ImageJobService.JobImage> images = invocation.getArgument(0);
            assertEquals("Extra Angle 1", images.get(0).angle());
            boolean truck = images.get(0).originalFilename().startsWith("8900");
            return new ImageAnalysisService.ImageAnalysis(truck ? "toy truck" : "brass clock", truck, List.of());
        });
        return analyzer;
    }

    private DropboxClient dropbox(List<DropboxClient.SharedFile> files) {
        DropboxClient dropbox = mock(DropboxClient.class);
        when(dropbox.listSharedFolder(LINK)).thenReturn(new DropboxClient.SharedFolder("Lot photos", files));
        return dropbox;
    }

    private static DropboxClient.SharedFile file(String name) {
        return new DropboxClient.SharedFile(name, 1024);
    }

    private ImagePlacementService placement() {
        ImagePlacementService placement = mock(ImagePlacementService.class);
        when(placement.place(any(), any(), any(), any())).thenAnswer(invocation -> solid(64, 64, Color.GREEN));
        return placement;
    }

    private static byte[] encoded(String format) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(solid(30, 20, Color.RED), format, output);
        return output.toByteArray();
    }

    private static BufferedImage solid(int width, int height, Color color) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        return image;
    }
}
