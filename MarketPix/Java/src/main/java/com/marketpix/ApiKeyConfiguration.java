package com.marketpix;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

@Component
public class ApiKeyConfiguration {

    private final String openAiApiKey;
    private final String r2AccessKeyId;
    private final String r2SecretAccessKey;

    public ApiKeyConfiguration() {
        Properties properties = new Properties();
        try (InputStream input = new ClassPathResource("application.properties").getInputStream()) {
            properties.load(input);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not load API keys from application.properties.", exception);
        }
        this.openAiApiKey = environmentOrFile(
                "OPENAI_API_KEY",
                loadKey(properties, "openai.api.key", "openai.api.key-file"));
        this.r2AccessKeyId = environmentOrFile("R2_ACCESS_KEY_ID", properties.getProperty("r2.access-key-id", ""));
        this.r2SecretAccessKey = environmentOrFile(
                "R2_SECRET_ACCESS_KEY", properties.getProperty("r2.secret-access-key", ""));
    }

    private static String environmentOrFile(String environmentVariable, String fileValue) {
        String environmentValue = System.getenv(environmentVariable);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }
        return fileValue == null ? "" : fileValue.trim();
    }

    // Prefers an inline key; otherwise reads it from a key file kept outside the repository.
    private static String loadKey(Properties properties, String keyProperty, String fileProperty) {
        String key = properties.getProperty(keyProperty, "").trim();
        String file = properties.getProperty(fileProperty, "").trim();
        if (!key.isEmpty() || file.isEmpty() || !Files.isReadable(Path.of(file))) {
            return key;
        }
        try {
            return Files.readString(Path.of(file)).trim();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read API key file " + file + ".", exception);
        }
    }

    public String getOpenAiApiKey() {
        return openAiApiKey;
    }

    public String getR2AccessKeyId() {
        return r2AccessKeyId;
    }

    public String getR2SecretAccessKey() {
        return r2SecretAccessKey;
    }
}
