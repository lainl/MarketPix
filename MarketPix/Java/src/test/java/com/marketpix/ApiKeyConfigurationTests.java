package com.marketpix;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiKeyConfigurationTests {

    @Test
    void trimsTheKeys() {
        ApiKeyConfiguration keys = new ApiKeyConfiguration(" openai-key ", "r2-id ", " r2-secret");

        assertEquals("openai-key", keys.getOpenAiApiKey());
        assertEquals("r2-id", keys.getR2AccessKeyId());
        assertEquals("r2-secret", keys.getR2SecretAccessKey());
    }

    @Test
    void emptyKeysStayEmpty() {
        ApiKeyConfiguration keys = new ApiKeyConfiguration("", "", "");

        assertEquals("", keys.getOpenAiApiKey());
        assertEquals("", keys.getR2AccessKeyId());
        assertEquals("", keys.getR2SecretAccessKey());
    }
}
