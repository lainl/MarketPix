package com.marketpix;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Converts images Java cannot decode, such as iPhone HEIC and AVIF, by calling ImageMagick. The
 * Docker image installs it; elsewhere it is found on the PATH, in the default Windows install folder,
 * or at the path set in {@code marketpix.imagemagick} (see {@link ImageMagickSettings}).
 */
public final class ImageMagickConverter {

    private static final Logger LOGGER = Logger.getLogger(ImageMagickConverter.class.getName());
    private static final long TIMEOUT_SECONDS = 120;
    private static final String LIMIT = "4096x4096>";
    private static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    private static volatile Optional<String> executable;
    private static volatile String configuredPath = "";

    private ImageMagickConverter() {
    }

    /** Sets the path to try first; the next conversion looks for ImageMagick again. */
    static synchronized void configure(String path) {
        configuredPath = path == null ? "" : path.trim();
        executable = null;
    }

    public static boolean isAvailable() {
        return executable().isPresent();
    }

    /**
     * Returns the image as an upright, 8-bit sRGB PNG no larger than 4096 pixels on its long edge.
     * {@code fileName} only supplies the extension, which helps ImageMagick recognise the format.
     */
    public static byte[] toPng(byte[] source, String fileName) throws IOException {
        String magick = executable().orElseThrow(() -> new IOException(
                "This image format needs ImageMagick, which is not installed on the server."));
        Path directory = Files.createTempDirectory("marketpix-convert-");
        try {
            Path input = directory.resolve("source" + extension(fileName));
            Path output = directory.resolve("converted.png");
            Path log = directory.resolve("imagemagick.log");
            Files.write(input, source);
            // [0] takes the first frame of multi-image files (HEIC bursts, animated GIFs, TIFF pages).
            Process process = new ProcessBuilder(magick, input + "[0]", "-auto-orient", "-colorspace", "sRGB",
                    "-depth", "8", "-resize", LIMIT, "png24:" + output)
                    .redirectErrorStream(true)
                    .redirectOutput(log.toFile())
                    .start();
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Converting the image took too long.");
            }
            if (process.exitValue() != 0 || !Files.isRegularFile(output)) {
                LOGGER.warning("ImageMagick could not convert " + fileName + ": "
                        + Files.readString(log, StandardCharsets.UTF_8).trim());
                throw new IOException("The image could not be converted; it may be damaged or an unsupported format.");
            }
            return Files.readAllBytes(output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Converting the image was interrupted.", exception);
        } finally {
            deleteQuietly(directory);
        }
    }

    static Optional<String> executable() {
        Optional<String> resolved = executable;
        if (resolved == null) {
            synchronized (ImageMagickConverter.class) {
                if (executable == null) {
                    executable = findExecutable();
                    LOGGER.info(executable.map(path -> "Converting other image formats with " + path)
                            .orElse("ImageMagick was not found; HEIC and AVIF images cannot be converted."));
                }
                resolved = executable;
            }
        }
        return resolved;
    }

    private static Optional<String> findExecutable() {
        List<String> candidates = new ArrayList<>();
        if (!configuredPath.isEmpty()) {
            candidates.add(configuredPath);
        }
        candidates.add("magick");
        if (WINDOWS) {
            // The installer adds itself to the PATH, but only for processes started afterwards.
            try (DirectoryStream<Path> installs = Files.newDirectoryStream(Path.of("C:/Program Files"), "ImageMagick*")) {
                installs.forEach(install -> candidates.add(install.resolve("magick.exe").toString()));
            } catch (IOException exception) {
                // No install folder.
            }
        } else {
            // ImageMagick 6 (Debian and Ubuntu packages). On Windows "convert" is a disk utility.
            candidates.add("convert");
        }
        return candidates.stream().filter(ImageMagickConverter::runs).findFirst();
    }

    private static boolean runs(String command) {
        try {
            Process process = new ProcessBuilder(command, "-version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return process.waitFor(15, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static String extension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return extension.matches("[a-z0-9]{1,5}") ? "." + extension : "";
    }

    private static void deleteQuietly(Path directory) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory)) {
            for (Path file : files) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Could not delete conversion folder " + directory, exception);
        }
    }
}
