package com.marketpix;

import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The editing step shared by single-item jobs and bulk jobs: places the item on its background and
 * checks the item's details against the original. A result that fails the check is regenerated
 * once; if it still fails, it is kept and flagged for the user.
 */
@Service
public class ListingPhotoEditor {

    private static final Logger LOGGER = Logger.getLogger(ListingPhotoEditor.class.getName());
    private static final int WORKING_EDGE = 2048;
    private static final int MAX_EDIT_ATTEMPTS = 2;

    private final ImageAnalysisService analysisService;
    private final ImagePlacementService placementService;

    public ListingPhotoEditor(ImageAnalysisService analysisService, ImagePlacementService placementService) {
        this.analysisService = analysisService;
        this.placementService = placementService;
    }

    /**
     * Returns the finished photo and, when the detail check did not pass, a warning that starts with
     * {@code label}. {@code background} may be null for a white studio backdrop; {@code angle} and
     * {@code objectDescription} may be blank when they are unknown.
     */
    public EditedPhoto edit(
            BufferedImage original, BufferedImage background, String objectDescription, String angle, String label) {
        BufferedImage photo = ImageFiles.limitLongEdge(original, WORKING_EDGE);
        BufferedImage edited = null;
        String warning = null;
        for (int attempt = 1; attempt <= MAX_EDIT_ATTEMPTS; attempt++) {
            edited = placementService.place(photo, background, objectDescription, angle);
            try {
                ImageAnalysisService.DetailCheck check = analysisService.verifyDetails(photo, edited, objectDescription);
                if (check.passed()) {
                    warning = null;
                    break;
                }
                warning = label + ": " + check.summary() + (check.issue().isBlank() ? "." : " — " + check.issue());
                LOGGER.info("Detail check failed, attempt " + attempt + ": " + warning);
            } catch (RuntimeException exception) {
                LOGGER.log(Level.WARNING, "Detail check could not run for " + label, exception);
                warning = label + ": the automatic detail check could not run; compare it with the original.";
                break;
            }
        }
        return new EditedPhoto(edited, warning);
    }

    public record EditedPhoto(BufferedImage image, String warning) {
    }
}
