package cn.yinsheng.ai.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
    String ollamaBaseUrl,
    String apiToken,
    String runtimeToken,
    long queueTimeoutMs,
    int chatConcurrency,
    int embeddingConcurrency,
    String defaultChatModel,
    String fallbackChatModel,
    String defaultEmbeddingModel,
    String fallbackEmbeddingModel,
    String chatKeepAlive,
    String embeddingKeepAlive,
    Integer embeddingNumGpu
) {
}
