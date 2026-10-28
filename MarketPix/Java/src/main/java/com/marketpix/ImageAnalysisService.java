package com.marketpix;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class ImageAnalysisService {

    private static final int ANALYSIS_EDGE = 1536;
    private static final String ANALYSIS_PROMPT = """
            Analyze the uploaded product photographs conservatively. They were taken by an auctioneer
            photographing one auction lot from several angles. Identify the primary physical object in each
            image and determine whether all images show the same object. Ignore backgrounds and shadows.
            Do not infer unseen features. Only report a mismatch when there is clear visual evidence.
            Mark objectFound false when no central object can be identified with reasonable confidence.
            Return only JSON with this shape:
            {"primaryObject":"short neutral description","sameObject":true,"images":[
              {"imageNumber":1,"objectFound":true,"objectConfidence":0.95,
               "matchesPrimaryObject":true,"reason":"brief visual evidence"}]}
            Include exactly one images entry for every photograph, preserving input order. Confidence values
            must be between 0 and 1.
            """;
    private static final String VERIFY_PROMPT = """
            You are quality-checking an AI-edited listing photo for an auction.
            Image A is the auctioneer's original photograph. Image B was produced by an image model that was
            told to remove the surroundings and show the very same item, completely unchanged, on a new
            background. The item is: %s.
            Compare the item in B with the item in A closely, region by region, and flag only differences
            that would mislead a bidder about what they are buying:
            - textChanged: text, numbers, logos or brand/model markings that are readable in A but read
              differently, are missing, or are replaced in B; or clearly readable new words in B where A
              shows no text at all.
            - partsChanged: a part of the item added, removed or clearly reshaped (a knob, button, lid,
              clip, antenna, cable, foot, handle), or a clearly different shape or proportions.
            - appearanceChanged: a clearly different color, material or finish, or visible damage,
              cracks, tape, stickers or heavy wear removed, hidden or added.
            - clutterLeft: objects that are not part of the item remain in B, or the item is cut off.
            Do not flag: lighting, white balance, sharpness, scale, position, a slightly different camera
            angle, the new background and its shadow; small labels that were illegible in A and are still
            not meaningfully readable in B; or minor variation in fine scuffs, dust and surface texture.
            Return only JSON:
            {"textChanged":false,"partsChanged":false,"appearanceChanged":false,"clutterLeft":false,
             "issue":"short description of every difference found, or empty"}
            """;

    private final OpenAiClient openAi;
    private final String model;

    public ImageAnalysisService(OpenAiClient openAi, @Value("${openai.vision.model}") String model) {
        this.openAi = openAi;
        this.model = model;
    }

    public ImageAnalysis analyze(List<ImageJobService.JobImage> images, String userContext) {
        List<Map<String, Object>> content = new ArrayList<>();
        content.add(OpenAiClient.text(ANALYSIS_PROMPT
                + (userContext == null || userContext.isBlank()
                ? ""
                : "\nUser-provided clarification to consider conservatively: " + userContext)));
        for (int index = 0; index < images.size(); index++) {
            ImageJobService.JobImage image = images.get(index);
            content.add(OpenAiClient.text("Picture " + (index + 1) + ":"));
            content.add(OpenAiClient.jpegImage(analysisJpeg(image)));
        }

        JsonNode analysis = openAi.chatJson(model, content, Duration.ofSeconds(150), "image analysis");
        try {
            String primaryObject = analysis.path("primaryObject").asText("").trim();
            JsonNode imagesNode = analysis.path("images");
            if (!imagesNode.isArray() || imagesNode.size() != images.size()) {
                throw new IOException("The number of analyzed images did not match the upload.");
            }

            List<PictureAnalysis> pictureResults = new ArrayList<>();
            for (int index = 0; index < imagesNode.size(); index++) {
                JsonNode picture = imagesNode.get(index);
                double objectConfidence = confidence(picture, "objectConfidence");
                if (picture.path("imageNumber").asInt(-1) != index + 1) {
                    throw new IOException("Image analysis returned results in an unexpected order.");
                }
                pictureResults.add(new PictureAnalysis(
                        index + 1,
                        images.get(index).angle(),
                        picture.path("objectFound").asBoolean(false),
                        objectConfidence,
                        picture.path("matchesPrimaryObject").asBoolean(false),
                        picture.path("reason").asText("").trim()));
            }
            return new ImageAnalysis(
                    primaryObject,
                    analysis.path("sameObject").asBoolean(false),
                    List.copyOf(pictureResults));
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Image recognition returned an invalid analysis.",
                    exception);
        }
    }

    /** Asks the vision model whether the edited photo still shows the item exactly as photographed. */
    public DetailCheck verifyDetails(BufferedImage original, BufferedImage edited, String objectDescription) {
        try {
            List<Map<String, Object>> content = List.of(
                    OpenAiClient.text(VERIFY_PROMPT.formatted(
                            objectDescription == null || objectDescription.isBlank() ? "the main object" : objectDescription)),
                    OpenAiClient.text("Image A (original):"),
                    OpenAiClient.jpegImage(ImageFiles.encodeJpeg(ImageFiles.limitLongEdge(original, ANALYSIS_EDGE), 0.9f)),
                    OpenAiClient.text("Image B (edited):"),
                    OpenAiClient.jpegImage(ImageFiles.encodeJpeg(ImageFiles.limitLongEdge(edited, ANALYSIS_EDGE), 0.9f)));
            JsonNode result = openAi.chatJson(model, content, Duration.ofSeconds(120), "detail check");
            return new DetailCheck(
                    result.path("textChanged").asBoolean(false),
                    result.path("partsChanged").asBoolean(false),
                    result.path("appearanceChanged").asBoolean(false),
                    result.path("clutterLeft").asBoolean(false),
                    result.path("issue").asText("").trim());
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR, "Could not prepare images for the detail check.", exception);
        }
    }

    private byte[] analysisJpeg(ImageJobService.JobImage image) {
        try {
            return ImageFiles.encodeJpeg(
                    ImageFiles.limitLongEdge(ImageFiles.readOriented(image.path()), ANALYSIS_EDGE), 0.9f);
        } catch (IOException exception) {
            throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Could not read an uploaded image for analysis.",
                    exception);
        }
    }

    private double confidence(JsonNode node, String property) throws IOException {
        JsonNode confidence = node.path(property);
        if (!confidence.isNumber() || confidence.asDouble() < 0 || confidence.asDouble() > 1) {
            throw new IOException("Image analysis returned invalid confidence values.");
        }
        return confidence.asDouble();
    }

    public record ImageAnalysis(String primaryObject, boolean sameObject, List<PictureAnalysis> images) {
    }

    public record PictureAnalysis(
            int imageNumber,
            String expectedAngle,
            boolean objectFound,
            double objectConfidence,
            boolean matchesPrimaryObject,
            String reason) {
    }

    public record DetailCheck(
            boolean textChanged, boolean partsChanged, boolean appearanceChanged, boolean clutterLeft, String issue) {
        public boolean passed() {
            return !textChanged && !partsChanged && !appearanceChanged && !clutterLeft;
        }

        public String summary() {
            List<String> problems = new ArrayList<>();
            if (textChanged) {
                problems.add("text or markings differ");
            }
            if (partsChanged) {
                problems.add("parts or shape differ");
            }
            if (appearanceChanged) {
                problems.add("colors, wear or stickers differ");
            }
            if (clutterLeft) {
                problems.add("other objects remain or the item is cut off");
            }
            return String.join(", ", problems);
        }
    }
}
