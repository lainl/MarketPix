package com.marketpix;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs the single-item mechanics over every photo in a shared Dropbox folder. Photos are named
 * {@code <itemCode>_<number>} (for example {@code 8900_03}, the third photo of item 8900), so they are
 * grouped by item: each item gets one object analysis, as a single-item job does, and its photos are
 * then edited with {@link ListingPhotoEditor}. A job is created with a cost estimate, starts once the
 * user confirms it, and then runs on the server while the page polls its progress.
 */
@Service
public class BulkJobService {

    private static final Logger LOGGER = Logger.getLogger(BulkJobService.class.getName());
    private static final Duration JOB_RETENTION = Duration.ofHours(1);
    private static final int PARALLEL_PHOTOS = 3;
    // Items are downloaded and analyzed a little ahead of the photo edits so the editors never wait.
    private static final int PARALLEL_ITEMS = 2;
    private static final int WORKING_EDGE = 2048;
    private static final int MAX_ANALYSIS_PHOTOS = 64;
    private static final long MAX_PHOTO_BYTES = 50L * 1024 * 1024;
    private static final Pattern ITEM_PHOTO = Pattern.compile("(.+)_(\\d{1,4})");
    private static final Set<String> JAVA_EXTENSIONS = Set.of(
            "jpg", "jpeg", "jpe", "jfif", "png", "gif", "bmp", "tif", "tiff", "webp", "psd");
    private static final Set<String> CONVERTED_EXTENSIONS = Set.of("heic", "heif", "hif", "avif");

    private final Path tempDirectory;
    private final DropboxClient dropbox;
    private final ImageAnalysisService analysisService;
    private final ListingPhotoEditor editor;
    private final JpegZipExporter exporter;
    private final ImageIdGenerator idGenerator;
    private final ImageRecordService records;
    private final double costPerPhoto;
    private final double costPerItem;
    private final int maxPhotos;
    private final ExecutorService runner = Executors.newCachedThreadPool();
    private final ConcurrentHashMap<UUID, BulkJob> jobs = new ConcurrentHashMap<>();

    public BulkJobService(
            @Value("${marketpix.images.temp-dir}") Path tempDirectory,
            DropboxClient dropbox,
            ImageAnalysisService analysisService,
            ListingPhotoEditor editor,
            JpegZipExporter exporter,
            ImageIdGenerator idGenerator,
            ImageRecordService records,
            @Value("${marketpix.bulk.cost-per-photo:0.25}") double costPerPhoto,
            @Value("${marketpix.bulk.cost-per-item:0.02}") double costPerItem,
            @Value("${marketpix.bulk.max-photos:500}") int maxPhotos) {
        this.tempDirectory = tempDirectory;
        this.dropbox = dropbox;
        this.analysisService = analysisService;
        this.editor = editor;
        this.exporter = exporter;
        this.idGenerator = idGenerator;
        this.records = records;
        this.costPerPhoto = costPerPhoto;
        this.costPerItem = costPerItem;
        this.maxPhotos = maxPhotos;
    }

    public BulkJobView create(String dropboxUrl) {
        return create(dropboxUrl, null);
    }

    /**
     * Lists the folder and returns a job awaiting confirmation, with its estimated cost.
     * {@code background} is optional; without one every photo goes on a white studio backdrop.
     */
    public BulkJobView create(String dropboxUrl, MultipartFile background) {
        String url = validateLink(dropboxUrl);
        DropboxClient.SharedFolder folder = dropbox.listSharedFolder(url);

        boolean canConvert = ImageMagickConverter.isAvailable();
        List<BulkPhoto> photos = new ArrayList<>();
        int unconvertible = 0;
        int oversized = 0;
        for (DropboxClient.SharedFile file : folder.files()) {
            String extension = extension(file.name());
            boolean image = JAVA_EXTENSIONS.contains(extension) || CONVERTED_EXTENSIONS.contains(extension);
            if (!image) {
                continue;
            }
            if (CONVERTED_EXTENSIONS.contains(extension) && !canConvert) {
                unconvertible++;
            } else if (file.size() > MAX_PHOTO_BYTES) {
                oversized++;
            } else {
                photos.add(BulkPhoto.of(file));
            }
        }
        String skippedNote = skippedNote(unconvertible, oversized);
        if (photos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No photos were found in \"" + folder.name() + "\"." + skippedNote);
        }
        if (photos.size() > maxPhotos) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "\"" + folder.name() + "\" has " + photos.size() + " photos. Bulk edit handles up to "
                            + maxPhotos + " at a time; split the folder and run each part.");
        }
        photos.sort(Comparator.comparing((BulkPhoto photo) -> itemOrder(photo.itemCode()))
                .thenComparing(BulkPhoto::itemCode, String.CASE_INSENSITIVE_ORDER)
                .thenComparingInt(BulkPhoto::number)
                .thenComparing(photo -> photo.file().name(), String.CASE_INSENSITIVE_ORDER));

        Path directory;
        Path backgroundPath = null;
        UUID id = UUID.randomUUID();
        try {
            Files.createDirectories(tempDirectory);
            directory = Files.createTempDirectory(tempDirectory, "marketpix-bulk-" + id + "-");
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not prepare the bulk job.", exception);
        }
        if (background != null && !background.isEmpty()) {
            try {
                backgroundPath = ImageJobService.storeReadable(
                        background, directory, "background", "The background is not a readable image.");
            } catch (IOException | RuntimeException exception) {
                deleteDirectory(directory);
                if (exception instanceof ResponseStatusException statusException) {
                    throw statusException;
                }
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Could not save the background image.", exception);
            }
        }
        BulkJob job = new BulkJob(id, url, folder.name(), directory, List.copyOf(photos),
                unconvertible + oversized, skippedNote, backgroundPath);
        jobs.put(id, job);
        return view(job);
    }

    public BulkJobView start(UUID jobId) {
        BulkJob job = findJob(jobId);
        synchronized (job) {
            if (!"AWAITING_CONFIRMATION".equals(job.status)) {
                return view(job);
            }
            job.status = "PROCESSING";
        }
        runner.submit(() -> run(job));
        return view(job);
    }

    /**
     * Cancels a job that has not started, or stops a running one after the photos in progress finish;
     * the finished photos are still offered as a ZIP.
     */
    public BulkJobView cancel(UUID jobId) {
        BulkJob job = findJob(jobId);
        synchronized (job) {
            if ("AWAITING_CONFIRMATION".equals(job.status)) {
                jobs.remove(job.id);
                deleteDirectory(job.directory);
                job.status = "CANCELLED";
                job.finishedAt = Instant.now();
            } else if ("PROCESSING".equals(job.status)) {
                job.cancelRequested = true;
                job.status = "CANCELLING";
            }
            return view(job);
        }
    }

    public BulkJobView status(UUID jobId) {
        return view(findJob(jobId));
    }

    public Path getDownload(UUID jobId) {
        BulkJob job = findJob(jobId);
        if (job.zipPath == null || !Files.isRegularFile(job.zipPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The ZIP download is not available.");
        }
        return job.zipPath;
    }

    public String getDownloadFilename(UUID jobId) {
        return findJob(jobId).archiveName + ".zip";
    }

    private void run(BulkJob job) {
        ExecutorService itemPool = Executors.newFixedThreadPool(Math.min(PARALLEL_ITEMS, job.items.size()));
        ExecutorService editPool = Executors.newFixedThreadPool(Math.min(PARALLEL_PHOTOS, job.photos.size()));
        try {
            BufferedImage background = job.backgroundPath == null ? null : ImageFiles.readOriented(job.backgroundPath);
            List<Future<List<Future<?>>>> items = new ArrayList<>();
            for (int index = 0; index < job.items.size(); index++) {
                int itemIndex = index;
                items.add(itemPool.submit(() -> prepareItem(job, itemIndex, background, editPool)));
            }
            for (Future<List<Future<?>>> item : items) {
                for (Future<?> edit : item.get()) {
                    edit.get();
                }
            }
            export(job);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            fail(job, "The bulk job was interrupted. Retry the folder.");
        } catch (IOException exception) {
            LOGGER.log(Level.SEVERE, "Could not read the background for bulk job " + job.id, exception);
            fail(job, "The background image could not be read. Choose another one and retry.");
        } catch (ExecutionException | RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Bulk job " + job.id + " failed", exception);
            fail(job, exception instanceof ResponseStatusException statusException && statusException.getReason() != null
                    ? statusException.getReason()
                    : "The bulk job could not be completed. Retry the folder.");
        } finally {
            itemPool.shutdownNow();
            editPool.shutdownNow();
        }
    }

    /**
     * Downloads and converts an item's photos, identifies the item once across all of them (as a
     * single-item job does), then queues each photo for editing with that description.
     */
    private List<Future<?>> prepareItem(
            BulkJob job, int itemIndex, BufferedImage background, ExecutorService editPool) {
        List<Integer> photoIndexes = job.items.get(itemIndex);
        List<Integer> ready = new ArrayList<>();
        List<ImageJobService.JobImage> images = new ArrayList<>();
        for (int index : photoIndexes) {
            if (job.cancelRequested) {
                return List.of();
            }
            String name = job.photos.get(index).file().name();
            try {
                byte[] source = dropbox.downloadSharedFile(job.url, name);
                BufferedImage photo = ImageFiles.limitLongEdge(ImageFiles.readOriented(source, name), WORKING_EDGE);
                Path working = job.directory.resolve(String.format("photo-%04d.jpg", index + 1));
                Files.write(working, ImageFiles.encodeJpeg(photo, 0.95f));
                images.add(new ImageJobService.JobImage("Extra Angle " + (images.size() + 1), name, "image/jpeg", working));
                ready.add(index);
            } catch (IOException | RuntimeException exception) {
                recordFailure(job, index, exception);
            }
        }

        String description = describe(job, itemIndex, images);
        List<Future<?>> edits = new ArrayList<>();
        for (int position = 0; position < ready.size(); position++) {
            int photoIndex = ready.get(position);
            Path working = images.get(position).path();
            edits.add(editPool.submit(() -> editPhoto(job, photoIndex, working, background, description)));
        }
        return edits;
    }

    /** Returns the item's description, or blank when it could not be identified (editing still runs). */
    private String describe(BulkJob job, int itemIndex, List<ImageJobService.JobImage> images) {
        if (images.isEmpty() || images.size() > MAX_ANALYSIS_PHOTOS || job.cancelRequested) {
            return "";
        }
        String itemCode = job.photos.get(job.items.get(itemIndex).get(0)).itemCode();
        try {
            ImageAnalysisService.ImageAnalysis analysis = analysisService.analyze(images, "");
            if (images.size() > 1 && !analysis.sameObject()) {
                job.itemWarnings[itemIndex] = "Item " + itemCode
                        + ": the photos may not all show the same object; check them before listing.";
            }
            return analysis.primaryObject();
        } catch (RuntimeException exception) {
            LOGGER.log(Level.WARNING, "Bulk job " + job.id + " could not analyze item " + itemCode, exception);
            return "";
        }
    }

    private void editPhoto(BulkJob job, int index, Path working, BufferedImage background, String description) {
        if (job.cancelRequested) {
            return;
        }
        String name = job.photos.get(index).file().name();
        try {
            ListingPhotoEditor.EditedPhoto edited = editor.edit(
                    ImageFiles.readOriented(working), background, description, null, name);
            Path output = job.directory.resolve(String.format("processed-%04d.jpg", index + 1));
            Files.write(output, ImageFiles.encodeJpeg(edited.image(), 0.95f));
            job.outputs[index] = output;
            job.warnings[index] = edited.warning();
            job.finished.incrementAndGet();
        } catch (IOException | RuntimeException exception) {
            recordFailure(job, index, exception);
        }
    }

    /** A photo that fails is recorded and skipped so the rest of the folder still runs. */
    private void recordFailure(BulkJob job, int index, Exception exception) {
        String name = job.photos.get(index).file().name();
        String reason;
        if (exception instanceof ResponseStatusException statusException && statusException.getReason() != null) {
            reason = statusException.getReason();
        } else if (exception instanceof IOException && exception.getMessage() != null) {
            reason = exception.getMessage();
        } else {
            reason = "it could not be processed.";
            LOGGER.log(Level.WARNING, "Bulk job " + job.id + " could not edit " + name, exception);
        }
        job.failures[index] = name + ": " + reason;
        job.failed.incrementAndGet();
        job.finished.incrementAndGet();
    }

    private void export(BulkJob job) {
        synchronized (job) {
            job.status = job.cancelRequested ? "CANCELLING" : "EXPORTING";
        }
        List<Path> outputs = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (int index = 0; index < job.outputs.length; index++) {
            if (job.outputs[index] != null) {
                outputs.add(job.outputs[index]);
                names.add(job.outputNames.get(index));
            }
        }
        String storageNote = "";
        if (!outputs.isEmpty()) {
            job.zipPath = exporter.export(job.archiveName, outputs, names, job.directory);
            storageNote = storeItems(job);
        }
        synchronized (job) {
            job.finishedAt = Instant.now();
            if (outputs.isEmpty()) {
                job.status = job.cancelRequested ? "CANCELLED" : "FAILED";
                job.message = job.cancelRequested
                        ? "The bulk job was stopped before any photo finished."
                        : "None of the photos could be edited. Check the reasons below and retry.";
                return;
            }
            job.status = job.cancelRequested ? "CANCELLED" : "COMPLETE";
            String edited = outputs.size() + " of " + job.photos.size() + " photos";
            job.message = (job.cancelRequested ? "Stopped. " + edited + " were finished" : "Done! " + edited + " were edited")
                    + (job.backgroundPath == null ? " onto a clean white studio background." : " into your background scene.")
                    + " AI editing can alter small details, so compare them with the originals before listing."
                    + storageNote;
        }
    }

    /** Saves each item's finished photos to cloud storage under an id of its own; returns a note for the message. */
    private String storeItems(BulkJob job) {
        if (!records.isEnabled()) {
            return "";
        }
        int stored = 0;
        int failed = 0;
        for (List<Integer> photoIndexes : job.items) {
            List<Path> finished = photoIndexes.stream()
                    .map(index -> job.outputs[index])
                    .filter(Objects::nonNull)
                    .toList();
            if (finished.isEmpty()) {
                continue;
            }
            try {
                records.store(idGenerator.next(), finished);
                stored++;
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "Bulk job " + job.id + " could not store an item in cloud storage", exception);
                failed++;
            }
        }
        String note = " " + stored + (stored == 1 ? " item was" : " items were") + " saved to cloud storage.";
        return failed == 0 ? note : note + " " + failed + " could not be saved; download the ZIP.";
    }

    private void fail(BulkJob job, String message) {
        synchronized (job) {
            job.status = "FAILED";
            job.message = message;
            job.finishedAt = Instant.now();
        }
    }

    private BulkJobView view(BulkJob job) {
        String status;
        String message;
        Instant finishedAt;
        synchronized (job) {
            status = job.status;
            message = job.message;
            finishedAt = job.finishedAt;
        }
        int total = job.photos.size();
        int items = job.items.size();
        if (message == null) {
            message = switch (status) {
                case "AWAITING_CONFIRMATION" -> "Found " + total + (total == 1 ? " photo" : " photos")
                        + " of " + items + (items == 1 ? " item" : " items")
                        + " in \"" + job.folderName + "\"." + job.skippedNote;
                case "CANCELLING" -> "Stopping after the photos in progress finish.";
                case "CANCELLED" -> "The bulk job was cancelled.";
                case "EXPORTING" -> "Creating the ZIP archive.";
                default -> "Identifying each item, placing it " + (job.backgroundPath == null
                        ? "on a white background" : "into your background scene") + " and checking its details.";
            };
        }
        boolean done = finishedAt != null;
        boolean downloadable = done && job.zipPath != null;
        List<String> warnings = new ArrayList<>(compact(job.itemWarnings));
        warnings.addAll(compact(job.warnings));
        return new BulkJobView(
                status,
                message,
                job.id.toString(),
                job.folderName,
                total,
                items,
                job.finished.get(),
                job.failed.get(),
                job.skipped,
                job.backgroundPath != null,
                costPerPhoto,
                costPerItem,
                estimate(total, items),
                done ? List.copyOf(warnings) : List.of(),
                done ? compact(job.failures) : List.of(),
                downloadable ? "/api/bulk/jobs/" + job.id + "/download" : null,
                downloadable ? job.archiveName + ".zip" : null);
    }

    private double estimate(int photos, int items) {
        return BigDecimal.valueOf(costPerPhoto).multiply(BigDecimal.valueOf(photos))
                .add(BigDecimal.valueOf(costPerItem).multiply(BigDecimal.valueOf(items)))
                .setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static List<String> compact(String[] values) {
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (value != null) {
                present.add(value);
            }
        }
        return List.copyOf(present);
    }

    private static String validateLink(String dropboxUrl) {
        String url = dropboxUrl == null ? "" : dropboxUrl.trim();
        try {
            URI uri = new URI(url);
            String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
            if ("https".equalsIgnoreCase(uri.getScheme()) && (host.equals("dropbox.com") || host.endsWith(".dropbox.com"))) {
                return url;
            }
        } catch (URISyntaxException exception) {
            // Reported below.
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Enter a Dropbox folder link, such as https://www.dropbox.com/scl/fo/…");
    }

    private static String skippedNote(int unconvertible, int oversized) {
        String note = "";
        if (unconvertible > 0) {
            note += " " + unconvertible + (unconvertible == 1 ? " HEIC or AVIF photo" : " HEIC or AVIF photos")
                    + " will be skipped because ImageMagick is not installed on the server.";
        }
        if (oversized > 0) {
            note += " " + oversized + (oversized == 1 ? " photo is" : " photos are")
                    + " over 50 MB and will be skipped.";
        }
        return note;
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String baseName(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /** Numeric item codes sort by value (8900 before 10000); others alphabetically after them. */
    private static String itemOrder(String itemCode) {
        return itemCode.matches("\\d{1,19}")
                ? "0" + "0".repeat(19 - itemCode.length()) + itemCode
                : "1" + itemCode.toLowerCase(Locale.ROOT);
    }

    /** {@code 8900_03.heic} becomes {@code 8900_03.jpg}; repeated names get a number so none overwrite. */
    static List<String> outputNames(List<BulkPhoto> photos) {
        Set<String> used = new HashSet<>();
        List<String> names = new ArrayList<>();
        for (BulkPhoto photo : photos) {
            String base = baseName(photo.file().name()).replaceAll("[\\\\/:*?\"<>|]", "_");
            String name = base + ".jpg";
            for (int copy = 2; !used.add(name.toLowerCase(Locale.ROOT)); copy++) {
                name = base + " (" + copy + ").jpg";
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    /** Groups photo indexes by item code; {@code photos} is already sorted by item. */
    private static List<List<Integer>> items(List<BulkPhoto> photos) {
        List<List<Integer>> items = new ArrayList<>();
        String current = null;
        for (int index = 0; index < photos.size(); index++) {
            String itemCode = photos.get(index).itemCode();
            if (current == null || !itemCode.equalsIgnoreCase(current)) {
                items.add(new ArrayList<>());
                current = itemCode;
            }
            items.get(items.size() - 1).add(index);
        }
        return items.stream().map(List::copyOf).toList();
    }

    private static String archiveName(String folderName) {
        String safe = folderName.replaceAll("[^A-Za-z0-9_-]+", "_").replaceAll("^_+|_+$", "");
        return (safe.isEmpty() ? "MarketPix" : safe) + "_edited";
    }

    private BulkJob findJob(UUID jobId) {
        BulkJob job = jobs.get(jobId);
        if (job == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The bulk job was not found or has expired.");
        }
        return job;
    }

    /** Running jobs are kept; others are removed an hour after they were created or finished. */
    @Scheduled(fixedDelay = 60_000)
    public void cleanExpiredJobs() {
        Instant now = Instant.now();
        jobs.forEach((id, job) -> {
            Instant since;
            synchronized (job) {
                since = "AWAITING_CONFIRMATION".equals(job.status) ? job.createdAt : job.finishedAt;
            }
            if (since != null && now.isAfter(since.plus(JOB_RETENTION)) && jobs.remove(id, job)) {
                deleteDirectory(job.directory);
            }
        });
    }

    @PreDestroy
    public void shutdown() {
        runner.shutdownNow();
    }

    private void deleteDirectory(Path directory) {
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    LOGGER.log(Level.WARNING, "Could not delete bulk-job file " + path, exception);
                }
            });
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not clean up bulk-job directory " + directory, exception);
        }
    }

    /** A photo in the folder; files that do not follow {@code <itemCode>_<number>} are their own item. */
    record BulkPhoto(DropboxClient.SharedFile file, String itemCode, int number) {
        static BulkPhoto of(DropboxClient.SharedFile file) {
            String base = baseName(file.name());
            Matcher matcher = ITEM_PHOTO.matcher(base);
            return matcher.matches()
                    ? new BulkPhoto(file, matcher.group(1), Integer.parseInt(matcher.group(2)))
                    : new BulkPhoto(file, base, 0);
        }
    }

    private static final class BulkJob {
        private final UUID id;
        private final String url;
        private final String folderName;
        private final String archiveName;
        private final Path directory;
        private final List<BulkPhoto> photos;
        private final List<List<Integer>> items;
        private final List<String> outputNames;
        private final int skipped;
        private final String skippedNote;
        private final Instant createdAt = Instant.now();
        private final AtomicInteger finished = new AtomicInteger();
        private final AtomicInteger failed = new AtomicInteger();
        private final Path[] outputs;
        private final String[] warnings;
        private final String[] failures;
        private final String[] itemWarnings;
        private final Path backgroundPath;
        private volatile boolean cancelRequested;
        private volatile Path zipPath;
        private String status = "AWAITING_CONFIRMATION";
        private String message;
        private Instant finishedAt;

        private BulkJob(UUID id, String url, String folderName, Path directory,
                        List<BulkPhoto> photos, int skipped, String skippedNote, Path backgroundPath) {
            this.id = id;
            this.url = url;
            this.folderName = folderName;
            this.archiveName = BulkJobService.archiveName(folderName);
            this.directory = directory;
            this.photos = photos;
            this.items = BulkJobService.items(photos);
            this.outputNames = BulkJobService.outputNames(photos);
            this.skipped = skipped;
            this.skippedNote = skippedNote;
            this.outputs = new Path[photos.size()];
            this.warnings = new String[photos.size()];
            this.failures = new String[photos.size()];
            this.itemWarnings = new String[items.size()];
            this.backgroundPath = backgroundPath;
        }
    }
}
