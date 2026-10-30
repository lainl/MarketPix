package com.marketpix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

@Service
public class ImageJobService {

    private static final Logger LOGGER = Logger.getLogger(ImageJobService.class.getName());
    private static final Duration JOB_RETENTION = Duration.ofHours(1);
    private static final int MAX_IMAGES = 64;
    private static final int PARALLEL_IMAGES = 3;
    private static final List<String> STANDARD_ANGLES = List.of(
            "Left Quarter View", "Right Quarter View", "Front", "Side Flat", "Back");

    private final Path tempDirectory;
    private final ImageAnalysisService analysisService;
    private final ListingPhotoEditor editor;
    private final JpegZipExporter exporter;
    private final ImageIdGenerator idGenerator;
    private final ImageRecordService records;
    private final MaintenanceService maintenance;
    private final ConcurrentHashMap<UUID, ImageJob> jobs = new ConcurrentHashMap<>();

    public ImageJobService(
            @Value("${marketpix.images.temp-dir}") Path tempDirectory,
            ImageAnalysisService analysisService,
            ListingPhotoEditor editor,
            JpegZipExporter exporter,
            ImageIdGenerator idGenerator,
            ImageRecordService records,
            MaintenanceService maintenance) {
        this.tempDirectory = tempDirectory;
        this.analysisService = analysisService;
        this.editor = editor;
        this.exporter = exporter;
        this.idGenerator = idGenerator;
        this.records = records;
        this.maintenance = maintenance;
    }

    /**
     * Jobs that are analyzing, editing or exporting right now; a deploy waits for these. A job waiting for
     * the user to answer in chat is not counted, since nobody may come back to it.
     */
    public int activeJobs() {
        return (int) jobs.values().stream()
                .filter(job -> List.of("ANALYZING", "PROCESSING", "EXPORTING").contains(job.status))
                .count();
    }

    public void createJob(
            List<String> angles,
            List<MultipartFile> uploads,
            Consumer<ImageJobEvent> events) {
        createJob(angles, uploads, null, "", events);
    }

    /** {@code background} is optional; without one the item is placed on a white studio backdrop. */
    public void createJob(
            List<String> angles,
            List<MultipartFile> uploads,
            MultipartFile background,
            String userContext,
            Consumer<ImageJobEvent> events) {
        createJob(angles, uploads, background, userContext, "", events);
    }

    /** {@code objectId} is optional; when filled in it is saved on the finished set and shown as its title. */
    public void createJob(
            List<String> angles,
            List<MultipartFile> uploads,
            MultipartFile background,
            String userContext,
            String objectId,
            Consumer<ImageJobEvent> events) {
        ImageJob job = null;
        Path jobDirectory = null;
        String imageId = idGenerator.next();
        int uploadCount = uploads == null ? 0 : uploads.size();
        try {
            maintenance.requireOpen();
            validateJobRequest(angles, uploads, background);
            emit(events, "VALIDATING", "Validating the uploaded images.", null, imageId, uploadCount, List.of(), null, null);
            Files.createDirectories(tempDirectory);
            UUID jobId = UUID.randomUUID();
            jobDirectory = Files.createTempDirectory(tempDirectory, "marketpix-job-" + jobId + "-");
            List<JobImage> images = storeImages(angles, uploads, jobDirectory);
            Path backgroundPath = hasFile(background) ? storeBackground(background, jobDirectory) : null;
            job = new ImageJob(jobId, imageId, jobDirectory, images, backgroundPath);
            job.objectId = objectId == null ? "" : objectId.trim();
            jobs.put(jobId, job);
            LOGGER.info("Image job " + jobId + " (" + imageId + ") created with " + images.size() + " picture(s)"
                    + (backgroundPath == null ? "." : " and a background scene."));
            analyze(job, userContext, events);
        } catch (Exception exception) {
            if (!(exception instanceof ResponseStatusException statusException)
                    || statusException.getStatusCode().is5xxServerError()) {
                LOGGER.log(Level.SEVERE, "Could not create image job " + imageId, exception);
            }
            if (job != null) {
                jobs.remove(job.id);
                deleteJobFiles(job);
            } else if (jobDirectory != null) {
                deleteDirectory(jobDirectory);
            }
            emitFailure(events, exception, job);
        }
    }

    public void confirmJob(
            UUID jobId,
            boolean ready,
            String userContext,
            Consumer<ImageJobEvent> events) {
        ImageJob job = findJob(jobId);
        synchronized (job) {
            if (!"WAITING_FOR_USER".equals(job.status)) {
                emit(events, "FAILED", "This image job is not waiting for confirmation.", job.id, job.imageId, job.images.size(), List.of(), null, null);
                return;
            }
            if (!ready) {
                emit(events, "WAITING_FOR_USER", "Please review the image warning and say “I'm ready” to continue.", job.id, job.imageId, job.images.size(), job.waitingWarnings, null, null);
                return;
            }
            if (maintenance.isDraining()) {
                emit(events, "WAITING_FOR_USER", "MarketPix is installing an update. Say “I'm ready” again in a few minutes.",
                        job.id, job.imageId, job.images.size(), job.waitingWarnings, null, null);
                return;
            }
            if (job.centralObjectUnclear) {
                if (userContext == null || userContext.isBlank()) {
                    emit(events, "WAITING_FOR_USER",
                            "I still can't reliably identify the same central object in every picture. Add context or replace the unclear image before continuing.",
                            job.id, job.imageId, job.images.size(), job.waitingWarnings, null, null);
                    return;
                }
                emit(events, "ANALYZING", "Rechecking the object identification with your context.", job.id, job.imageId, job.images.size(), List.of(), null, null);
                try {
                    job.analysis = analysisService.analyze(job.images, userContext);
                } catch (RuntimeException exception) {
                    LOGGER.log(Level.WARNING, "Could not reanalyze image job " + job.id, exception);
                    emitFailure(events, exception, job);
                    return;
                }
                job.centralObjectUnclear = hasCentralObjectProblem(job.analysis);
                if (job.centralObjectUnclear) {
                    job.waitingWarnings = centralObjectWarnings(job.analysis);
                    emit(events, "WAITING_FOR_USER",
                            "The images still do not provide a reliable object match. Replace the unclear picture with a clearer view and try again.",
                            job.id, job.imageId, job.images.size(), job.waitingWarnings, null, null);
                    return;
                }
            }

            finalizeJob(job, events);
        }
    }

    public Path getDownload(UUID jobId) {
        ImageJob job = findJob(jobId);
        synchronized (job) {
            if (!"COMPLETE".equals(job.status) || job.zipPath == null || !Files.isRegularFile(job.zipPath)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The ZIP download is not available.");
            }
            return job.zipPath;
        }
    }

    public String getDownloadFilename(UUID jobId) {
        return findJob(jobId).imageId + ".zip";
    }

    public Path getPreview(UUID jobId, int imageNumber) {
        ImageJob job = findJob(jobId);
        List<Path> outputs = job.outputs;
        if (!"COMPLETE".equals(job.status) || imageNumber < 1 || imageNumber > outputs.size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The finished image is not available.");
        }
        return outputs.get(imageNumber - 1);
    }

    public String getPreviewFilename(UUID jobId, int imageNumber) {
        return JpegZipExporter.entryName(findJob(jobId).imageId, imageNumber - 1);
    }

    private void analyze(ImageJob job, String userContext, Consumer<ImageJobEvent> events) {
        emit(events, "ANALYZING", "Identifying the central object.", job.id, job.imageId, job.images.size(), List.of(), null, null);
        job.analysis = analysisService.analyze(job.images, userContext);
        if (hasCentralObjectProblem(job.analysis)) {
            job.centralObjectUnclear = true;
            job.waitingWarnings = centralObjectWarnings(job.analysis);
            job.status = "WAITING_FOR_USER";
            LOGGER.info("Image job " + job.id + " is waiting for the user: the central object is unclear.");
            emit(events, "WAITING_FOR_USER",
                    "Please review the unclear image, add context in chat, or replace it before processing.",
                    job.id, job.imageId, job.images.size(), job.waitingWarnings, null, null);
            return;
        }
        finalizeJob(job, events);
    }

    private void finalizeJob(ImageJob job, Consumer<ImageJobEvent> rawEvents) {
        if (!job.finalizationStarted.compareAndSet(false, true)) {
            emit(rawEvents, "FAILED", "This image job is already being processed.", job.id, job.imageId, job.images.size(), List.of(), null, null);
            return;
        }
        // Pictures are processed in parallel and report progress from worker threads.
        Consumer<ImageJobEvent> events = event -> {
            synchronized (rawEvents) {
                rawEvents.accept(event);
            }
        };

        int total = job.images.size();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(PARALLEL_IMAGES, total));
        try {
            job.status = "PROCESSING";
            emit(events, "PROCESSING", "MarketPix is processing images.", job.id, job.imageId, total, List.of(), null, null);
            emit(events, "PROCESSING", progressMessage(0, total), job.id, job.imageId, total, List.of(), null, null);
            BufferedImage background = job.backgroundPath == null ? null : ImageFiles.readOriented(job.backgroundPath);
            String objectDescription = job.analysis == null ? "" : job.analysis.primaryObject();
            AtomicInteger finished = new AtomicInteger();

            List<Future<ProcessedImage>> futures = new ArrayList<>();
            for (int index = 0; index < total; index++) {
                int imageIndex = index;
                futures.add(pool.submit(() -> {
                    ProcessedImage processed = processImage(job, imageIndex, background, objectDescription);
                    emit(events, "PROCESSING", progressMessage(finished.incrementAndGet(), total),
                            job.id, job.imageId, total, List.of(), null, null);
                    return processed;
                }));
            }

            List<Path> outputs = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            for (int index = 0; index < total; index++) {
                ProcessedImage processed;
                try {
                    processed = futures.get(index).get();
                } catch (ExecutionException exception) {
                    throw pictureFailure(index, exception.getCause());
                }
                outputs.add(processed.path());
                if (processed.warning() != null) {
                    warnings.add(processed.warning());
                }
            }

            job.status = "EXPORTING";
            emit(events, "EXPORTING", "Creating the ZIP archive.", job.id, job.imageId, total, List.of(), null, null);
            job.zipPath = exporter.export(job.imageId, outputs, job.directory);
            job.outputs = List.copyOf(outputs);

            boolean stored = false;
            if (records.isEnabled()) {
                emit(events, "EXPORTING", "Saving your images to cloud storage.", job.id, job.imageId, total, List.of(), null, null);
                try {
                    if (job.objectId.isEmpty()) {
                        records.store(job.imageId, outputs);
                    } else {
                        records.store(job.imageId, outputs, job.objectId);
                    }
                    stored = true;
                } catch (RuntimeException exception) {
                    LOGGER.log(Level.WARNING, "Could not store image set " + job.imageId + " in cloud storage", exception);
                    warnings.add("Cloud storage: these pictures could not be saved online, so download the ZIP now.");
                }
            }
            job.status = "COMPLETE";
            LOGGER.info("Image job " + job.id + " (" + job.imageId + ") complete: " + total + " picture(s), "
                    + warnings.size() + " flagged, " + (stored ? "saved to cloud storage." : "not saved to cloud storage."));

            List<String> previewUrls = new ArrayList<>();
            for (int index = 0; index < total; index++) {
                previewUrls.add("/api/images/jobs/" + job.id + "/images/" + (index + 1));
            }
            String message = "Your images are ready! " + (total == 1 ? "The picture was" : "All " + total + " pictures were")
                    + (job.backgroundPath == null
                    ? " edited onto a clean white studio background."
                    : " edited into your background scene.")
                    + " AI editing can alter small details, so compare them with the originals before listing.";
            if (stored) {
                message += " Saved as " + job.imageId + ".";
            }
            if (!warnings.isEmpty()) {
                message += " Please review the flagged pictures before listing.";
            }
            emit(events, "COMPLETE", message, job.id, job.imageId, total, List.copyOf(warnings),
                    "/api/images/jobs/" + job.id + "/download", job.imageId + ".zip", List.copyOf(previewUrls), stored);
        } catch (IOException exception) {
            job.status = "FAILED";
            LOGGER.log(Level.SEVERE, "Could not read the background for image job " + job.id, exception);
            emitFailure(events, exception, job);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            job.status = "FAILED";
            emitFailure(events, exception, job);
        } catch (RuntimeException exception) {
            job.status = "FAILED";
            if (!(exception instanceof ResponseStatusException statusException)
                    || statusException.getStatusCode().is5xxServerError()) {
                LOGGER.log(Level.SEVERE, "Could not process image job " + job.id, exception);
            } else {
                LOGGER.warning("Image job " + job.id + " failed: " + statusException.getReason());
            }
            emitFailure(events, exception, job);
        } finally {
            pool.shutdownNow();
        }
    }

    private static String progressMessage(int finished, int total) {
        return "Removing the surroundings, placing the item on the background and checking its details ("
                + finished + " of " + total + " done).";
    }

    /** Edits one picture; see {@link ListingPhotoEditor} for the detail check and retry. */
    private ProcessedImage processImage(ImageJob job, int index, BufferedImage background, String objectDescription)
            throws IOException {
        JobImage image = job.images.get(index);
        String label = "Picture " + (index + 1) + " (" + image.angle() + ")";
        ListingPhotoEditor.EditedPhoto edited = editor.edit(
                ImageFiles.readOriented(image.path()), background, objectDescription, image.angle(), label);

        Path output = job.directory.resolve(String.format("processed-%02d.jpg", index + 1));
        Files.write(output, ImageFiles.encodeJpeg(edited.image(), 0.95f));
        return new ProcessedImage(output, edited.warning());
    }

    private RuntimeException pictureFailure(int index, Throwable cause) {
        if (!(cause instanceof ResponseStatusException statusException)) {
            LOGGER.log(Level.SEVERE, "Picture " + (index + 1) + " failed", cause);
            return new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Picture " + (index + 1) + " could not be processed. Your original images are still available; retry.",
                    cause);
        }
        String reason = statusException.getReason() == null ? "it could not be processed." : statusException.getReason();
        return new ResponseStatusException(statusException.getStatusCode(),
                "Picture " + (index + 1) + ": " + reason + " Your original images are still available; replace it or retry.",
                cause);
    }

    private void validateJobRequest(List<String> angles, List<MultipartFile> uploads, MultipartFile background) {
        if (uploads == null || uploads.isEmpty() || uploads.size() > MAX_IMAGES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Add at least one angle picture (up to " + MAX_IMAGES + ").");
        }
        if (angles == null || angles.size() != uploads.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Each uploaded image must have an angle label.");
        }
        if (hasFile(background) && !mayBeImage(background)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The background must be an image.");
        }
        for (int index = 0; index < uploads.size(); index++) {
            MultipartFile upload = uploads.get(index);
            if (upload.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Picture " + (index + 1) + " is empty.");
            }
            if (!mayBeImage(upload)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Picture " + (index + 1) + " must be an image.");
            }
            requireKnownAngle(angles.get(index));
        }
    }

    /** Pictures arrive in the order the page shows its slots; only the label has to be one it knows. */
    private void requireKnownAngle(String angle) {
        if (STANDARD_ANGLES.contains(angle) || (angle != null && angle.matches("Extra Angle [1-9][0-9]*"))) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "An uploaded image has an unknown angle label.");
    }

    private List<JobImage> storeImages(List<String> angles, List<MultipartFile> uploads, Path directory) throws IOException {
        List<JobImage> images = new ArrayList<>();
        for (int index = 0; index < uploads.size(); index++) {
            MultipartFile upload = uploads.get(index);
            Path path = storeReadable(upload, directory, String.format("input-%02d", index + 1),
                    "Picture " + (index + 1) + " is not a readable image.");
            images.add(new JobImage(angles.get(index), upload.getOriginalFilename(), upload.getContentType(), path));
        }
        return List.copyOf(images);
    }

    private static boolean hasFile(MultipartFile file) {
        return file != null && !file.isEmpty();
    }

    /** Browsers send an empty or generic type for formats they do not know, such as HEIC on Windows. */
    private static boolean mayBeImage(MultipartFile file) {
        String contentType = file.getContentType();
        return contentType == null || contentType.isBlank() || contentType.startsWith("image/")
                || "application/octet-stream".equals(contentType);
    }

    private Path storeBackground(MultipartFile background, Path directory) throws IOException {
        return storeReadable(background, directory, "background", "The background is not a readable image.");
    }

    /**
     * Saves an upload and checks that it decodes. A format Java cannot read directly (HEIC, AVIF, ...)
     * is converted once here and kept as an upright PNG, so later steps never convert it again.
     */
    static Path storeReadable(MultipartFile upload, Path directory, String name, String unreadableMessage)
            throws IOException {
        Path path = directory.resolve(name + ".upload");
        upload.transferTo(path);
        try {
            if (ImageIO.read(path.toFile()) != null) {
                return path;
            }
        } catch (IOException | RuntimeException exception) {
            // Damaged or unusual file; conversion below gets one more try.
        }
        BufferedImage converted;
        try {
            converted = ImageFiles.readOriented(Files.readAllBytes(path), upload.getOriginalFilename());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, unreadableMessage, exception);
        }
        Path png = directory.resolve(name + ".png");
        Files.write(png, ImageFiles.encodePng(converted));
        Files.delete(path);
        return png;
    }

    private boolean hasCentralObjectProblem(ImageAnalysisService.ImageAnalysis analysis) {
        return !analysis.sameObject() || analysis.primaryObject().isBlank()
                || analysis.images().stream().anyMatch(image ->
                !image.objectFound() || image.objectConfidence() < 0.6 || !image.matchesPrimaryObject());
    }

    private List<String> centralObjectWarnings(ImageAnalysisService.ImageAnalysis analysis) {
        List<String> warnings = new ArrayList<>();
        for (ImageAnalysisService.PictureAnalysis image : analysis.images()) {
            if (!image.objectFound() || image.objectConfidence() < 0.6) {
                warnings.add("Can't reliably identify the central object in picture " + image.imageNumber()
                        + " (" + image.expectedAngle() + "). Please upload a clearer image or provide context.");
            } else if (!image.matchesPrimaryObject() || !analysis.sameObject()) {
                warnings.add("Picture " + image.imageNumber() + " may show a different object from the other pictures. "
                        + "Please check it or provide context before processing.");
            }
        }
        return warnings.isEmpty()
                ? List.of("The same central object could not be confirmed across the uploaded pictures. Please provide context or replace an unclear image.")
                : List.copyOf(warnings);
    }

    private ImageJob findJob(UUID jobId) {
        ImageJob job = jobs.get(jobId);
        if (job == null || Instant.now().isAfter(job.createdAt.plus(JOB_RETENTION))) {
            if (job != null && jobs.remove(jobId, job)) {
                deleteJobFiles(job);
            }
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The image job was not found or has expired.");
        }
        return job;
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanExpiredJobs() {
        Instant now = Instant.now();
        jobs.forEach((id, job) -> {
            if (now.isAfter(job.createdAt.plus(JOB_RETENTION)) && jobs.remove(id, job)) {
                deleteJobFiles(job);
            }
        });
    }

    private void deleteJobFiles(ImageJob job) {
        deleteDirectory(job.directory);
    }

    private void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "Could not delete expired image-job file " + path, exception);
                }
            });
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not clean up image-job directory " + directory, exception);
        }
    }

    private void emitFailure(Consumer<ImageJobEvent> events, Exception exception, ImageJob job) {
        String message = exception instanceof ResponseStatusException responseException
                ? responseException.getReason()
                : "Image processing failed. Your original images are still available; check them and retry.";
        emit(events, "FAILED", message == null ? "Image processing failed." : message,
                job == null ? null : job.id,
                job == null ? null : job.imageId,
                job == null ? 0 : job.images.size(),
                List.of(), null, null);
    }

    private void emit(
            Consumer<ImageJobEvent> events,
            String status,
            String message,
            UUID jobId,
            String imageId,
            int imageCount,
            List<String> warnings,
            String downloadUrl,
            String downloadFilename) {
        emit(events, status, message, jobId, imageId, imageCount, warnings, downloadUrl, downloadFilename, List.of(), false);
    }

    private void emit(
            Consumer<ImageJobEvent> events,
            String status,
            String message,
            UUID jobId,
            String imageId,
            int imageCount,
            List<String> warnings,
            String downloadUrl,
            String downloadFilename,
            List<String> previewUrls,
            boolean stored) {
        events.accept(new ImageJobEvent(
                status,
                message,
                jobId == null ? null : jobId.toString(),
                imageId,
                imageCount,
                warnings,
                downloadUrl,
                downloadFilename,
                previewUrls,
                stored));
    }

    public static final class ImageJob {
        private final UUID id;
        private final String imageId;
        private final Path directory;
        private final List<JobImage> images;
        private final Path backgroundPath;
        private final Instant createdAt = Instant.now();
        private final AtomicBoolean finalizationStarted = new AtomicBoolean();
        private volatile ImageAnalysisService.ImageAnalysis analysis;
        private volatile Path zipPath;
        private volatile List<Path> outputs = List.of();
        private volatile String objectId = "";
        private volatile String status = "ANALYZING";
        private volatile boolean centralObjectUnclear;
        private volatile List<String> waitingWarnings = List.of();

        private ImageJob(UUID id, String imageId, Path directory, List<JobImage> images, Path backgroundPath) {
            this.id = id;
            this.imageId = imageId;
            this.directory = directory;
            this.images = images;
            this.backgroundPath = backgroundPath;
        }
    }

    public record JobImage(String angle, String originalFilename, String mediaType, Path path) {
    }

    private record ProcessedImage(Path path, String warning) {
    }
}
