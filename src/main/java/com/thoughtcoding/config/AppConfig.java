package com.thoughtcoding.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 应用配置类，包含模型和工具的配置
 * 具体包括：ToolsConfig, ToolConfig, ModelConfig
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Data
public class AppConfig {

    @JsonProperty("models")
    private Map<String, ModelConfig> models;

    @JsonProperty("defaultModel")
    private String defaultModel;

    @JsonProperty("tools")
    private ToolsConfig tools = new ToolsConfig(); // Ensure tools is initialized

    @JsonProperty("ai")
    private AIConfig ai = new AIConfig(); // AI行为配置


    // Getters and Setters
    public Map<String, ModelConfig> getModels() {
        return models;
    }

    public void setModels(Map<String, ModelConfig> models) {
        this.models = models;
    }

    public ModelConfig getModelConfig(String modelName) {
        return models != null ? models.get(modelName) : null;
    }

    public void setDefaultModel(String defaultModel) {
        this.defaultModel = defaultModel;
    }

    public ToolsConfig getTools() {
        if (tools == null) {
            tools = new ToolsConfig(); // Ensure tools is not null
        }
        return tools;
    }

    public void setTools(ToolsConfig tools) {
        this.tools = tools;
    }

    public AIConfig getAi() {
        if (ai == null) {
            ai = new AIConfig();
        }
        return ai;
    }

    public void setAi(AIConfig ai) {
        this.ai = ai;
    }


    public String getDefaultModel() {
        // 如果配置了defaultModel，使用配置的值
        if (defaultModel != null && !defaultModel.trim().isEmpty()) {
            return defaultModel;
        }

        // 如果没有配置defaultModel，使用第一个模型作为默认
        if (models != null && !models.isEmpty()) {
            return models.keySet().iterator().next();
        }

        // 如果连模型都没有配置，返回null或抛出异常
        return null;
    }


    @Data
    public static class ModelConfig {
        @JsonProperty("name")
        private String name;

        @JsonProperty("baseURL")
        private String baseURL;

        @JsonProperty("apiKey")
        private String apiKey;

        @JsonProperty("streaming")
        private boolean streaming = true;

        @JsonProperty("maxTokens")
        private Integer maxTokens = 4096;

        @JsonProperty("temperature")
        private Double temperature = 0.7;

        @JsonProperty("topP")
        private Double topP = 0.9;

        @JsonProperty("timeout")
        private Integer timeout = 60;

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseURL() {
            return baseURL;
        }
        public Double getTemperature() {
            return temperature != null ? temperature : 0.7;
        }

        public void setBaseURL(String baseURL) {
            this.baseURL = baseURL;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public boolean isStreaming() {
            return streaming;
        }

        public void setStreaming(boolean streaming) {
            this.streaming = streaming;
        }

        public Integer getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(Integer maxTokens) {
            this.maxTokens = maxTokens;
        }
    }

    @Data
    public static class ToolsConfig {
        @JsonProperty("bash")
        private ToolConfig bash = new ToolConfig();

        @JsonProperty("read")
        private ToolConfig read = new ToolConfig();

        @JsonProperty("write")
        private ToolConfig write = new ToolConfig();

        @JsonProperty("edit")
        private ToolConfig edit = new ToolConfig();

        @JsonProperty("glob")
        private ToolConfig glob = new ToolConfig();

        public ToolConfig getBash() {
            if (bash == null) bash = new ToolConfig();
            return bash;
        }

        public void setBash(ToolConfig bash) {
            this.bash = bash;
        }

        public ToolConfig getRead() {
            if (read == null) read = new ToolConfig();
            return read;
        }

        public void setRead(ToolConfig read) {
            this.read = read;
        }

        public ToolConfig getWrite() {
            if (write == null) write = new ToolConfig();
            return write;
        }

        public void setWrite(ToolConfig write) {
            this.write = write;
        }

        public ToolConfig getEdit() {
            if (edit == null) edit = new ToolConfig();
            return edit;
        }

        public void setEdit(ToolConfig edit) {
            this.edit = edit;
        }

        public ToolConfig getGlob() {
            if (glob == null) glob = new ToolConfig();
            return glob;
        }

        public void setGlob(ToolConfig glob) {
            this.glob = glob;
        }
    }

    @Data
    public static class ToolConfig {
        @JsonProperty("enabled")
        private boolean enabled = true;

        @JsonProperty("maxFileSize")
        private Long maxFileSize= 10485760L; // 设置默认值

        @JsonProperty("allowedCommands")
        private String[] allowedCommands;

        @JsonProperty("timeoutSeconds")
        private Integer timeoutSeconds = 30;

        @JsonProperty("allowedLanguages")
        private String[] allowedLanguages = {"java", "python", "javascript"};

        // Getters and Setters
        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public Long getMaxFileSize() {
            return maxFileSize;
        }

        public void setMaxFileSize(Long maxFileSize) {
            this.maxFileSize = maxFileSize;
        }

        public String[] getAllowedCommands() {
            return allowedCommands;
        }

        public void setAllowedCommands(String[] allowedCommands) {
            this.allowedCommands = allowedCommands;
        }

        public Integer getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(Integer timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public String[] getAllowedLanguages() {
            return allowedLanguages;
        }

        public void setAllowedLanguages(String[] allowedLanguages) {
            this.allowedLanguages = allowedLanguages;
        }
    }

    /**
     * AI行为配置
     */
    @Data
    public static class AIConfig {
        @JsonProperty("autoProcessToolResults")
        private boolean autoProcessToolResults = true; // 默认true：工具执行后把结果反馈给AI，形成 agentic 循环

        @JsonProperty("maxToolIterations")
        private int maxToolIterations = 10; // agentic 循环单次用户输入内的最大工具轮次上限

        public boolean isAutoProcessToolResults() {
            return autoProcessToolResults;
        }

        public void setAutoProcessToolResults(boolean autoProcessToolResults) {
            this.autoProcessToolResults = autoProcessToolResults;
        }

        public int getMaxToolIterations() {
            return maxToolIterations;
        }

        public void setMaxToolIterations(int maxToolIterations) {
            this.maxToolIterations = maxToolIterations;
        }
    }
}