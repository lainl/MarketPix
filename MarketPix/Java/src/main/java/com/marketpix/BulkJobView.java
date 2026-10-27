package com.marketpix;

import java.util.List;

/** A bulk job's state as the bulk edit page polls it. */
public record BulkJobView(
        String status,
        String message,
        String jobId,
        String folderName,
        int total,
        int items,
        int finished,
        int failed,
        int skipped,
        boolean backgroundScene,
        double costPerPhoto,
        double costPerItem,
        double estimatedCost,
        List<String> warnings,
        List<String> failures,
        String downloadUrl,
        String downloadFilename) {
}
