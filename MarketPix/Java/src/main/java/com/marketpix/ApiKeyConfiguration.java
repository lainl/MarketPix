package com.marketpix;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The secret keys, read from application.properties like every other setting; their values come from the
 * {@code .env} file or real environment variables (see {@code spring.config.import} there).
 */
@Component
public class ApiKeyConfiguration {

    private final String openAiApiKey;
    private final String r2AccessKeyId;
    private final String r2SecretAccessKey;

    public ApiKeyConfiguration(
            @Value("${openai.api.key}") String openAiApiKey,
            @Value("${r2.access-key-id}") String r2AccessKeyId,
            @Value("${r2.secret-access-key}") String r2SecretAccessKey) {
        this.openAiApiKey = openAiApiKey.trim();
        this.r2AccessKeyId = r2AccessKeyId.trim();
        this.r2SecretAccessKey = r2SecretAccessKey.trim();
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
