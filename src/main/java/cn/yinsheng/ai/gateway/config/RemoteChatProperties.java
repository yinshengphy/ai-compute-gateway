package cn.yinsheng.ai.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "remote-chat")
public record RemoteChatProperties(
    boolean enabled, String baseUrl, String apiToken, String primaryModel,
    String fallbackModel, int timeoutSeconds, int circuitSeconds) {
}
