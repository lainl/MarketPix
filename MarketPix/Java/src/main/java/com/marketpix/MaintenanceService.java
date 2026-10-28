package com.marketpix;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Lets a deployment pause new work: while draining, new image and bulk jobs are refused with a clear
 * message, and jobs already running finish normally. The deploy script waits for them, then restarts.
 */
@Service
public class MaintenanceService {

    private static final Logger LOGGER = Logger.getLogger(MaintenanceService.class.getName());

    private final AtomicBoolean draining = new AtomicBoolean();

    public void startDraining() {
        if (draining.compareAndSet(false, true)) {
            LOGGER.info("Update pending: new jobs are paused until the running ones finish.");
        }
    }

    public void stopDraining() {
        if (draining.compareAndSet(true, false)) {
            LOGGER.info("Update cancelled: new jobs are accepted again.");
        }
    }

    public boolean isDraining() {
        return draining.get();
    }

    /** Throws when an update is waiting, so a new job does not start right before a restart. */
    public void requireOpen() {
        if (draining.get()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "MarketPix is installing an update. Try again in a few minutes.");
        }
    }
}
