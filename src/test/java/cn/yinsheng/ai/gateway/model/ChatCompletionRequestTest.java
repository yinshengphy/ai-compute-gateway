package cn.yinsheng.ai.gateway.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatCompletionRequestTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void shouldAcceptVisionContentAndTools() throws Exception {
    String json = """
        {
          "model": "vision-model",
          "stream": true,
          "messages": [{
            "role": "user",
            "content": [
              {"type": "text", "text": "describe"},
              {"type": "image_url", "image_url": {"url": "data:image/png;base64,AA=="}}
            ]
          }],
          "tools": [{"type": "function", "function": {"name": "weather", "parameters": {"type": "object"}}}]
        }
        """;

    ChatCompletionRequest request = objectMapper.readValue(json, ChatCompletionRequest.class);

    assertThat(request.messages()).hasSize(1);
    assertThat(request.messages().get(0).content()).isInstanceOf(List.class);
    assertThat(request.tools()).hasSize(1);
  }
}
