package cn.yinsheng.ai.gateway.model;

import java.util.List;
import java.util.Map;

public record ChatCompletionRequest(
    String model,
    List<Message> messages,
    Integer max_tokens,
    Double temperature,
    Boolean stream,
    List<Map<String, Object>> tools,
    Object tool_choice
) {
  public record Message(
      String role,
      Object content,
      List<String> images,
      List<Map<String, Object>> tool_calls,
      String tool_call_id,
      String name
  ) {
  }
}
