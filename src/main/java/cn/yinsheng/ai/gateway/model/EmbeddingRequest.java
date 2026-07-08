package cn.yinsheng.ai.gateway.model;

public record EmbeddingRequest(
    String model,
    Object input
) {
}
