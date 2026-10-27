package com.marketpix;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageJobServiceTests {

    private static final String ID = "261340102026J2378";

    @TempDir
    Path tempDirectory;

    @Test
    void exportsEveryAngleAsSequentialJpegsAndEmitsRealJobStages() throws Exception {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), anyString())).thenReturn(clearAnalysis(3));
        ImagePlacementService placement = placement();
        ImageJobService service = service(analyzer, placement);
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(
                List.of("Left Quarter View", "Front", "Extra Angle 1"),
                List.of(image("first.png", Color.RED), image("third.png", Color.BLUE), image("last.png", Color.GREEN)),
                events::add);

        ImageJobEvent complete = events.get(events.size() - 1);
        assertEquals("COMPLETE", complete.status());
        assertEquals("261340102026J2378.zip", complete.downloadFilename());
        assertEquals(3, complete.previewUrls().size());
        assertTrue(complete.warnings().isEmpty());
        assertTrue(complete.message().contains("white studio background"));
        assertTrue(events.stream().anyMatch(event ->
                event.status().equals("PROCESSING") && event.message().equals("MarketPix is processing images.")));
        assertTrue(events.stream().anyMatch(event ->
                event.status().equals("PROCESSING") && event.message().contains("3 of 3 done")));
        assertTrue(events.stream().anyMatch(event -> event.status().equals("EXPORTING")));
        verify(placement).place(any(BufferedImage.class), isNull(), eq("toy truck"), eq("Left Quarter View"));
        verify(placement).place(any(BufferedImage.class), isNull(), eq("toy truck"), eq("Extra Angle 1"));

        Path archive = service.getDownload(UUID.fromString(complete.jobId()));
        try (ZipInputStream zip = new ZipInputStream(java.nio.file.Files.newInputStream(archive))) {
            assertEquals("261340102026J2378_01.jpg", zip.getNextEntry().getName());
            assertJpegEntry(zip);
            assertEquals("261340102026J2378_02.jpg", zip.getNextEntry().getName());
            assertJpegEntry(zip);
            assertEquals("261340102026J2378_03.jpg", zip.getNextEntry().getName());
            assertJpegEntry(zip);
            assertEquals(null, zip.getNextEntry());
        }
        assertNotNull(ImageIO.read(service.getPreview(UUID.fromString(complete.jobId()), 2).toFile()));
    }

    @Test
    void exportsTheImageModelResultAndPassesTheOptionalBackground() throws Exception {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), anyString())).thenReturn(clearAnalysis(1));
        ImagePlacementService placement = placement();
        ImageJobService service = service(analyzer, placement);
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(
                List.of("Front"),
                List.of(image("original.png", Color.RED)),
                image("background.png", Color.BLUE),
                "",
                events::add);

        ImageJobEvent complete = events.get(events.size() - 1);
        assertEquals("COMPLETE", complete.status());
        assertTrue(complete.message().contains("your background scene"));
        verify(placement).place(any(BufferedImage.class), notNull(), eq("toy truck"), eq("Front"));
        BufferedImage exported = ImageIO.read(service.getPreview(UUID.fromString(complete.jobId()), 1).toFile());
        Color center = new Color(exported.getRGB(exported.getWidth() / 2, exported.getHeight() / 2));
        assertTrue(center.getGreen() > 200 && center.getRed() < 60, "the edited result is exported: " + center);
    }

    @Test
    void rejectsABackgroundThatIsNotAnImage() {
        ImageJobService service = service(analyzer(), placement());
        List<ImageJobEvent> events = new ArrayList<>();
        MockMultipartFile notes = new MockMultipartFile("background", "notes.txt", "text/plain", new byte[]{1, 2, 3});

        service.createJob(List.of("Front"), List.of(image("view.png", Color.RED)), notes, "", events::add);

        assertEquals("FAILED", events.get(0).status());
        assertTrue(events.get(0).message().contains("background"));
    }

    @Test
    void requiresAtLeastOneAngleImage() {
        ImageJobService service = service(analyzer(), placement());
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(List.of(), List.of(), events::add);

        assertEquals("FAILED", events.get(0).status());
        assertTrue(events.get(0).message().contains("at least one angle"));
    }

    @Test
    void regeneratesOnceWhenTheDetailCheckFailsAndKeepsAPassingResult() {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), anyString())).thenReturn(clearAnalysis(1));
        when(analyzer.verifyDetails(any(), any(), anyString()))
                .thenReturn(new ImageAnalysisService.DetailCheck(true, false, false, false, "label text was rewritten"))
                .thenReturn(new ImageAnalysisService.DetailCheck(false, false, false, false, ""));
        ImagePlacementService placement = placement();
        ImageJobService service = service(analyzer, placement);
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(List.of("Front"), List.of(image("view.png", Color.RED)), events::add);

        ImageJobEvent complete = events.get(events.size() - 1);
        assertEquals("COMPLETE", complete.status());
        assertTrue(complete.warnings().isEmpty());
        verify(placement, times(2)).place(any(), any(), anyString(), anyString());
    }

    @Test
    void flagsPicturesThatStillFailTheDetailCheck() {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), anyString())).thenReturn(clearAnalysis(1));
        when(analyzer.verifyDetails(any(), any(), anyString()))
                .thenReturn(new ImageAnalysisService.DetailCheck(true, false, false, false, "the model number changed"));
        ImagePlacementService placement = placement();
        ImageJobService service = service(analyzer, placement);
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(List.of("Front"), List.of(image("view.png", Color.RED)), events::add);

        ImageJobEvent complete = events.get(events.size() - 1);
        assertEquals("COMPLETE", complete.status());
        assertEquals(1, complete.warnings().size());
        assertTrue(complete.warnings().get(0).contains("Picture 1 (Front)"));
        assertTrue(complete.warnings().get(0).contains("text or markings differ"));
        assertTrue(complete.warnings().get(0).contains("the model number changed"));
        verify(placement, times(2)).place(any(), any(), anyString(), anyString());
    }

    @Test
    void reportsWhichPictureCouldNotBeEdited() {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), anyString())).thenReturn(clearAnalysis(2));
        ImagePlacementService placement = mock(ImagePlacementService.class);
        when(placement.place(any(), any(), anyString(), eq("Front"))).thenReturn(edited());
        when(placement.place(any(), any(), anyString(), eq("Side Flat")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI rejected the image editing request."));
        ImageJobService service = service(analyzer, placement);
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(List.of("Front", "Side Flat"),
                List.of(image("a.png", Color.RED), image("b.png", Color.RED)),
                events::add);

        ImageJobEvent failed = events.get(events.size() - 1);
        assertEquals("FAILED", failed.status());
        assertTrue(failed.message().startsWith("Picture 2:"));
        assertTrue(failed.message().contains("rejected the image editing request"));
    }

    @Test
    void theAngleSlotOnlyHintsTheImageModelAndNeverStopsTheJob() {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), anyString())).thenReturn(clearAnalysis(1));
        ImagePlacementService placement = placement();
        ImageJobService service = service(analyzer, placement);
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(
                List.of("Left Quarter View"),
                List.of(image("view.png", Color.ORANGE)),
                events::add);

        assertTrue(events.stream().noneMatch(event -> event.status().equals("WAITING_FOR_USER")));
        assertEquals("COMPLETE", events.get(events.size() - 1).status());
        verify(placement).place(any(BufferedImage.class), isNull(), eq("toy truck"), eq("Left Quarter View"));
    }

    @Test
    void unclearCentralObjectRequiresContextAndReanalysisBeforeProcessing() {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), anyString()))
                .thenReturn(unclearAnalysis())
                .thenReturn(clearAnalysis(1));
        ImageJobService service = service(analyzer, placement());
        List<ImageJobEvent> initialEvents = new ArrayList<>();

        service.createJob(
                List.of("Front"),
                List.of(image("unclear.png", Color.GRAY)),
                initialEvents::add);

        ImageJobEvent waiting = initialEvents.get(initialEvents.size() - 1);
        assertEquals("WAITING_FOR_USER", waiting.status());
        UUID jobId = UUID.fromString(waiting.jobId());

        List<ImageJobEvent> noContextEvents = new ArrayList<>();
        service.confirmJob(jobId, true, "", noContextEvents::add);
        assertEquals("WAITING_FOR_USER", noContextEvents.get(noContextEvents.size() - 1).status());

        List<ImageJobEvent> contextEvents = new ArrayList<>();
        service.confirmJob(jobId, true, "This is a red toy truck.", contextEvents::add);
        assertEquals("COMPLETE", contextEvents.get(contextEvents.size() - 1).status());

        List<ImageJobEvent> duplicateEvents = new ArrayList<>();
        service.confirmJob(jobId, true, "This is a red toy truck.", duplicateEvents::add);
        assertEquals("FAILED", duplicateEvents.get(0).status());
        assertFalse(duplicateEvents.stream().anyMatch(event -> event.status().equals("COMPLETE")));
    }

    @Test
    void carriesClarificationContextIntoTheInitialAnalysis() {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), eq("This is a red toy truck."))).thenReturn(clearAnalysis(1));
        ImageJobService service = service(analyzer, placement());
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(
                List.of("Front"),
                List.of(image("front.png", Color.RED)),
                null,
                "This is a red toy truck.",
                events::add);

        assertEquals("COMPLETE", events.get(events.size() - 1).status());
        verify(analyzer).analyze(anyList(), eq("This is a red toy truck."));
    }

    @Test
    void savesTheFinishedImagesUnderTheGeneratedIdWhenCloudStorageIsOn() {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), anyString())).thenReturn(clearAnalysis(1));
        ImageRecordService records = mock(ImageRecordService.class);
        when(records.isEnabled()).thenReturn(true);
        ImageJobService service = service(analyzer, placement(), records);
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(List.of("Front"), List.of(image("view.png", Color.RED)), events::add);

        ImageJobEvent complete = events.get(events.size() - 1);
        assertEquals("COMPLETE", complete.status());
        assertEquals(ID, complete.imageId());
        assertTrue(complete.stored());
        assertTrue(complete.message().contains("Saved as " + ID));
        verify(records).store(eq(ID), anyList());
    }

    @Test
    void stillFinishesWithAWarningWhenCloudStorageFails() {
        ImageAnalysisService analyzer = analyzer();
        when(analyzer.analyze(anyList(), anyString())).thenReturn(clearAnalysis(1));
        ImageRecordService records = mock(ImageRecordService.class);
        when(records.isEnabled()).thenReturn(true);
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.BAD_GATEWAY, "down"))
                .when(records).store(anyString(), anyList());
        ImageJobService service = service(analyzer, placement(), records);
        List<ImageJobEvent> events = new ArrayList<>();

        service.createJob(List.of("Front"), List.of(image("view.png", Color.RED)), events::add);

        ImageJobEvent complete = events.get(events.size() - 1);
        assertEquals("COMPLETE", complete.status());
        assertFalse(complete.stored());
        assertTrue(complete.warnings().get(0).contains("Cloud storage"));
    }

    @Test
    void cleansPartialUploadDirectoryWhenImageDataIsInvalid() throws Exception {
        ImageJobService service = service(analyzer(), placement());
        List<ImageJobEvent> events = new ArrayList<>();
        MockMultipartFile invalidImage = new MockMultipartFile(
                "images", "invalid.png", "image/png", new byte[]{1, 2, 3});

        service.createJob(List.of("Front"), List.of(invalidImage), events::add);

        assertEquals("FAILED", events.get(events.size() - 1).status());
        try (var files = java.nio.file.Files.list(tempDirectory)) {
            assertEquals(0, files.count());
        }
    }

    private ImageJobService service(ImageAnalysisService analyzer, ImagePlacementService placement) {
        return service(analyzer, placement, mock(ImageRecordService.class));
    }

    private ImageJobService service(
            ImageAnalysisService analyzer, ImagePlacementService placement, ImageRecordService records) {
        ImageIdGenerator ids = mock(ImageIdGenerator.class);
        when(ids.next()).thenReturn(ID);
        return new ImageJobService(
                tempDirectory, analyzer, new ListingPhotoEditor(analyzer, placement), new JpegZipExporter(),
                ids, records);
    }

    private ImageAnalysisService analyzer() {
        ImageAnalysisService analyzer = mock(ImageAnalysisService.class);
        when(analyzer.verifyDetails(any(), any(), anyString()))
                .thenReturn(new ImageAnalysisService.DetailCheck(false, false, false, false, ""));
        return analyzer;
    }

    /** Stands in for the image model: always returns a solid green "edited" picture. */
    private ImagePlacementService placement() {
        ImagePlacementService placement = mock(ImagePlacementService.class);
        when(placement.place(any(), any(), anyString(), anyString())).thenAnswer(invocation -> edited());
        return placement;
    }

    private static BufferedImage edited() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                image.setRGB(x, y, Color.GREEN.getRGB());
            }
        }
        return image;
    }

    private ImageAnalysisService.ImageAnalysis clearAnalysis(int imageCount) {
        List<ImageAnalysisService.PictureAnalysis> pictures = new ArrayList<>();
        for (int index = 0; index < imageCount; index++) {
            pictures.add(new ImageAnalysisService.PictureAnalysis(
                    index + 1,
                    index == 0 ? "Left Quarter View" : "Front",
                    true,
                    0.95,
                    true,
                    "The same product is centered."));
        }
        return new ImageAnalysisService.ImageAnalysis("toy truck", true, pictures);
    }

    private ImageAnalysisService.ImageAnalysis unclearAnalysis() {
        return new ImageAnalysisService.ImageAnalysis(
                "",
                false,
                List.of(new ImageAnalysisService.PictureAnalysis(
                        1, "Front", false, 0.2, false, "No clear central object.")));
    }

    private MockMultipartFile image(String name, Color color) {
        try {
            BufferedImage image = new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < image.getHeight(); y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    image.setRGB(x, y, color.getRGB());
                }
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return new MockMultipartFile("images", name, "image/png", output.toByteArray());
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private void assertJpegEntry(ZipInputStream zip) throws Exception {
        byte[] bytes = zip.readAllBytes();
        assertTrue(bytes.length > 4);
        assertEquals((byte) 0xff, bytes[0]);
        assertEquals((byte) 0xd8, bytes[1]);
        assertNotNull(ImageIO.read(new ByteArrayInputStream(bytes)));
    }
}
