package com.marketpix.api;

import com.marketpix.BulkJobService;
import com.marketpix.ImageJobService;
import com.marketpix.MaintenanceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

/**
 * Used by the deploy script from inside the app's own container: reports the running jobs and pauses new
 * ones before a restart. Every request that does not come from this machine's loopback address is
 * answered as if the route did not exist (the reverse proxy also blocks the path).
 */
@RestController
@RequestMapping("/api/internal")
public class InternalController {

    private final MaintenanceService maintenance;
    private final ImageJobService imageJobs;
    private final BulkJobService bulkJobs;

    public InternalController(MaintenanceService maintenance, ImageJobService imageJobs, BulkJobService bulkJobs) {
        this.maintenance = maintenance;
        this.imageJobs = imageJobs;
        this.bulkJobs = bulkJobs;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        if (!fromLoopback(request)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(snapshot());
    }

    @PostMapping("/drain")
    public ResponseEntity<Map<String, Object>> drain(HttpServletRequest request) {
        if (!fromLoopback(request)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        maintenance.startDraining();
        return ResponseEntity.ok(snapshot());
    }

    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume(HttpServletRequest request) {
        if (!fromLoopback(request)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        maintenance.stopDraining();
        return ResponseEntity.ok(snapshot());
    }

    private Map<String, Object> snapshot() {
        int imageJobCount = imageJobs.activeJobs();
        int bulkJobCount = bulkJobs.activeJobs();
        return Map.of(
                "draining", maintenance.isDraining(),
                "activeJobs", imageJobCount + bulkJobCount,
                "activeImageJobs", imageJobCount,
                "activeBulkJobs", bulkJobCount,
                "bulkPhotosLeft", bulkJobs.photosLeft());
    }

    private static boolean fromLoopback(HttpServletRequest request) {
        try {
            return InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (UnknownHostException exception) {
            return false;
        }
    }
}
