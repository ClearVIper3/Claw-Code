package com.thoughtcoding.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppConfigCompatibilityTest {

    @Test
    void ignoresLegacyToolNamesWithoutDiscardingModelConfiguration() throws Exception {
        String yaml = """
                models:
                  deepseek-v1:
                    name: deepseek-chat
                    baseURL: https://api.deepseek.com/v1
                    apiKey: test-key
                defaultModel: deepseek-v1
                tools:
                  fileManager:
                    enabled: true
                  commandExec:
                    enabled: true
                """;

        AppConfig config = new ObjectMapper(new YAMLFactory()).readValue(yaml, AppConfig.class);

        assertEquals("deepseek-v1", config.getDefaultModel());
        assertNotNull(config.getModelConfig("deepseek-v1"));
        assertTrue(config.getTools().getBash().isEnabled());
        assertTrue(config.getTools().getRead().isEnabled());
        assertTrue(config.getAi().isSubagentWorktreeIsolation());
    }

    @Test
    void canDisableSubagentWorktreeIsolationExplicitly() throws Exception {
        String yaml = """
                ai:
                  subagentWorktreeIsolation: false
                """;

        AppConfig config = new ObjectMapper(new YAMLFactory()).readValue(yaml, AppConfig.class);

        assertFalse(config.getAi().isSubagentWorktreeIsolation());
    }
}
