package com.marketpix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Asks the image model to separate the item from its surroundings and place it on a background:
 * either the scene the user uploaded or a clean white studio backdrop.
 */
@Service
public class ImagePlacementService {

    private static final Logger LOGGER = Logger.getLogger(ImagePlacementService.class.getName());
    private static final int REFERENCE_EDGE = 1536;
    private static final String PRESERVATION_RULES = """
            The item must stay exactly as photographed. This has priority over every other instruction:
            - Same shape, proportions, viewing angle and perspective. %s
            - Same colors, materials, finish, wear, scratches, dents, dirt, stickers, tape and labels.
            - Every printed, engraved or embossed character, number and logo exactly as in the photo. Where
              text is too small or blurry to read, keep it equally small and blurry; never invent or
              "correct" words.
            - Do not add, remove, repair, complete, straighten, recolor, restyle or beautify any part of the
              item, and do not invent parts that are hidden in the photo.
            """;
    private static final String WHITE_PROMPT = """
            Image 1 is an auctioneer's photo of an auction lot: %s.
            Produce a professional listing photo of this exact item, as a product photographer would in
            Photoshop:
            - Remove everything that is not part of the item: furniture, table top, walls, floor, other
              objects, hands, cables, papers and clutter, including their shadows and reflections.
            - Place the item alone, centered and filling about 80%% of the frame, on a clean seamless pure
              white studio background.
            - Studio finish: soft even lighting, neutral white balance, clean exposure, crisp focus, and a
              soft natural contact shadow directly beneath the item.
            %s""";
    private static final String SCENE_PROMPT = """
            Image 1 is an auctioneer's photo of an auction lot: %s.
            Image 2 is the background scene chosen for the listing.
            Produce a professional listing photo of this exact item placed into the scene from image 2, as a
            product photographer would composite it in Photoshop:
            - Take only the item from image 1. Leave behind everything that is not part of the item:
              furniture, table top, walls, floor, other objects, hands, cables, papers and clutter, including
              their shadows and reflections.
            - Keep the scene from image 2 as it is: same surfaces, colors, lighting and mood. Do not add new
              objects to it.
            - Stand the item naturally on the scene's main surface, centered and filling about 80%% of the
              frame, at a perspective that matches the scene, with lighting matched to the scene and a
              realistic contact shadow consistent with the scene's light.
            - Do not let colors or textures from the scene change the item itself.
            %s""";

    private final OpenAiClient openAi;
    private final String model;
    private final String size;

    public ImagePlacementService(
            OpenAiClient openAi,
            @Value("${openai.image.model:gpt-image-2.5-flare}") String model,
            @Value("${openai.image.size:2048x2048}") String size) {
        this.openAi = openAi;
        this.model = model;
        this.size = size;
    }

    /** Returns the finished listing photo; {@code background} may be null for a white studio backdrop. */
    public BufferedImage place(BufferedImage photo, BufferedImage background, String objectDescription, String angle) {
        String item = objectDescription == null || objectDescription.isBlank()
                ? "the main object in the photo"
                : objectDescription.trim();
        String rules = PRESERVATION_RULES.formatted(angleHint(angle));
        String prompt = background == null
                ? WHITE_PROMPT.formatted(item, rules)
                : SCENE_PROMPT.formatted(item, rules);

        List<byte[]> references = new ArrayList<>();
        try {
            references.add(ImageFiles.encodePng(ImageFiles.limitLongEdge(photo, REFERENCE_EDGE)));
            if (background != null) {
                references.add(ImageFiles.encodePng(ImageFiles.limitLongEdge(background, REFERENCE_EDGE)));
            }
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare an image for editing.", exception);
        }
        return decode(request(references, prompt));
    }

    /** The slot the user picked is only a hint; the photo itself decides what the view looks like. */
    private static String angleHint(String angle) {
        if (angle == null || angle.isBlank()) {
            return "Keep the view exactly as photographed.";
        }
        String label = angle.startsWith("Extra Angle") ? "an additional view" : "the \"" + angle + "\" view";
        return "The photographer filed this photo as " + label
                + "; use that only as a hint and keep the view exactly as photographed.";
    }

    private byte[] request(List<byte[]> references, String prompt) {
        try {
            return openAi.editImage(model, references, prompt, size, Duration.ofMinutes(5), "image editing");
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.SERVICE_UNAVAILABLE
                    && exception.getStatusCode() != HttpStatus.GATEWAY_TIMEOUT) {
                throw exception;
            }
            LOGGER.log(Level.WARNING, "Retrying image edit after: " + exception.getReason());
            return openAi.editImage(model, references, prompt, size, Duration.ofMinutes(5), "image editing");
        }
    }

    private static BufferedImage decode(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image != null) {
                return ImageFiles.toRgb(image);
            }
        } catch (IOException exception) {
            // Reported below.
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The image model returned an unreadable image.");
    }
}
