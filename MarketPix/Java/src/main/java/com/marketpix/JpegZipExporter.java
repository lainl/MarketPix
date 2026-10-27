package com.marketpix;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Service
public class JpegZipExporter {

    /** Packages finished JPEG files as {@code <imageId>_01.jpg, <imageId>_02.jpg, ...}. */
    public Path export(String imageId, List<Path> jpegs, Path jobDirectory) {
        List<String> entryNames = new ArrayList<>();
        for (int index = 0; index < jpegs.size(); index++) {
            entryNames.add(entryName(imageId, index));
        }
        return export(imageId, jpegs, entryNames, jobDirectory);
    }

    /** Packages finished JPEG files under the given entry names as {@code <archiveName>.zip}. */
    public Path export(String archiveName, List<Path> jpegs, List<String> entryNames, Path jobDirectory) {
        Path zipPath = jobDirectory.resolve(archiveName + ".zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (int index = 0; index < jpegs.size(); index++) {
                zip.putNextEntry(new ZipEntry(entryNames.get(index)));
                Files.copy(jpegs.get(index), zip);
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException exception) {
            try {
                Files.deleteIfExists(zipPath);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not create the ZIP archive.",
                    exception);
        }
        return zipPath;
    }

    public static String entryName(String imageId, int index) {
        return imageId + "_" + String.format("%02d", index + 1) + ".jpg";
    }
}
