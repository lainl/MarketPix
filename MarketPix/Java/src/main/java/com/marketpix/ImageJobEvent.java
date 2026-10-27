package com.marketpix;

import java.util.List;

/** {@code imageId} is the id the finished set is stored under; {@code stored} is true once it is in cloud storage. */
public record ImageJobEvent(
        String status,
        String message,
        String jobId,
        String imageId,
        int imageCount,
        List<String> warnings,
        String downloadUrl,
        String downloadFilename,
        List<String> previewUrls,
        boolean stored) {
}
