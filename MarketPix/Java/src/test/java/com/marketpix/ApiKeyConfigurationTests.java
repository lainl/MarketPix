package com.marketpix;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiKeyConfigurationTests {

    @Test
    void loadsApiKeysFromApplicationPropertiesInsteadOfEnvironmentPropertyOverrides() throws IOException {
        Properties fileProperties = new Properties();
        try (InputStream input = getClass().getClassLoader().getResourceAsStream("application.properties")) {
            if (input == null) {
                throw new IOException("application.properties was not found on the test classpath.");
            }
            fileProperties.load(input);
        }

        String previousKey = System.getProperty("openai.api.key");
        try {
            System.setProperty("openai.api.key", "system-property-openai-key");

            ApiKeyConfiguration apiKeys = new ApiKeyConfiguration();

            assertEquals(expectedOpenAiKey(fileProperties), apiKeys.getOpenAiApiKey());
        } finally {
            restoreSystemProperty("openai.api.key", previousKey);
        }
    }

    private String expectedOpenAiKey(Properties fileProperties) throws IOException {
        String key = fileProperties.getProperty("openai.api.key", "").trim();
        Path file = Path.of(fileProperties.getProperty("openai.api.key-file", ""));
        if (!key.isEmpty() || !Files.isReadable(file)) {
            return key;
        }
        return Files.readString(file).trim();
    }

    private void restoreSystemProperty(String name, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, previousValue);
        }
    }
}
