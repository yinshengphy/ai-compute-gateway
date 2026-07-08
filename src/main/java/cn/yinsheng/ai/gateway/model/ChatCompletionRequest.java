package cn.yinsheng.ai.gateway.model;

import java.util.List;

public record ChatCompletionRequest(
    String model,
    List<Message> messages,
    Integer max_tokens,
    Double temperature,
    Boolean stream
) {
  public record Message(String role, String content) {
  }
}
