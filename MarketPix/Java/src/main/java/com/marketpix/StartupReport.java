package com.marketpix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.logging.Logger;

/**
 * Writes one summary to the log when MarketPix starts and a line when it stops, so the server log shows
 * which services are set up. Only whether a key is present is logged, never a key itself.
 */
@Component
public class StartupReport {

    private static final Logger LOGGER = Logger.getLogger(StartupReport.class.getName());

    private final ApiKeyConfiguration apiKeys;
    private final R2Client r2;
    private final String port;
    private final String imageModel;
    private final String bulkImageModel;
    private final String visionModel;
    private final String chatModel;
    private final boolean dropboxConfigured;
    private final boolean bulkAiEditing;
    private final String sessionFile;
    private final String bulkJobsDirectory;
    private final String timezone;

    public StartupReport(
            ApiKeyConfiguration apiKeys,
            R2Client r2,
            @Value("${server.port}") String port,
            @Value("${openai.image.model}") String imageModel,
            @Value("${openai.image.bulk-model}") String bulkImageModel,
            @Value("${openai.vision.model}") String visionModel,
            @Value("${openai.chat.model}") String chatModel,
            @Value("${dropbox.refresh-token}") String dropboxRefreshToken,
            @Value("${dropbox.access-token}") String dropboxAccessToken,
            @Value("${marketpix.bulk.ai-editing}") boolean bulkAiEditing,
            @Value("${marketpix.auth.session-file}") String sessionFile,
            @Value("${marketpix.bulk.jobs-dir}") String bulkJobsDirectory,
            @Value("${marketpix.id.timezone}") String timezone) {
        this.apiKeys = apiKeys;
        this.r2 = r2;
        this.port = port;
        this.imageModel = imageModel;
        this.bulkImageModel = bulkImageModel.isBlank() ? imageModel : bulkImageModel;
        this.visionModel = visionModel;
        this.chatModel = chatModel;
        this.dropboxConfigured = !dropboxRefreshToken.isBlank() || !dropboxAccessToken.isBlank();
        this.bulkAiEditing = bulkAiEditing;
        this.sessionFile = sessionFile;
        this.bulkJobsDirectory = bulkJobsDirectory;
        this.timezone = timezone;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void started() {
        LOGGER.info("MarketPix is running on port " + port + "."
                + " OpenAI key: " + (apiKeys.getOpenAiApiKey().isBlank() ? "MISSING" : "set")
                + ". Cloud storage (R2): " + (r2.isConfigured() ? "on" : "OFF, nothing is saved online")
                + ". Dropbox: " + (dropboxConfigured ? "set" : "MISSING, bulk edit cannot read folders")
                + ". Models: image " + imageModel + ", bulk image " + bulkImageModel + ", vision " + visionModel
                + ", chat " + chatModel
                + ". Bulk AI editing: " + (bulkAiEditing ? "on" : "OFF, bulk photos are saved as is")
                + ". Logins kept in: " + (sessionFile.isBlank() ? "memory only (lost on restart)" : sessionFile)
                + ". Bulk jobs kept in: " + bulkJobsDirectory
                + ". Id time zone: " + (timezone.isBlank() ? "server default" : timezone) + ".");
    }

    @EventListener(ContextClosedEvent.class)
    public void stopping() {
        LOGGER.info("MarketPix is shutting down.");
    }
}
