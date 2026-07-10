package cn.yinsheng.ai.gateway.api;

import cn.yinsheng.ai.gateway.model.ChatCompletionRequest;
import cn.yinsheng.ai.gateway.model.EmbeddingRequest;
import cn.yinsheng.ai.gateway.ollama.OllamaProxy;
import cn.yinsheng.ai.gateway.queue.ComputeQueue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OpenAiCompatibleController {
  private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleController.class);
  private final OllamaProxy ollamaProxy;
  private final ComputeQueue computeQueue;
  private final ObjectMapper objectMapper;

  public OpenAiCompatibleController(OllamaProxy ollamaProxy, ComputeQueue computeQueue, ObjectMapper objectMapper) {
    this.ollamaProxy = ollamaProxy;
    this.computeQueue = computeQueue;
    this.objectMapper = objectMapper;
  }

  @PostMapping("/v1/chat/completions")
  public Object chat(@RequestBody ChatCompletionRequest request, HttpServletResponse response) throws Exception {
    if (Boolean.TRUE.equals(request.stream())) {
      response.setStatus(HttpServletResponse.SC_OK);
      response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
      response.setCharacterEncoding(StandardCharsets.UTF_8.name());
      try (ComputeQueue.Lease ignored = computeQueue.enterChat()) {
        String id = "chatcmpl-" + Instant.now().toEpochMilli();
        var outputStream = response.getOutputStream();
        String model = ollamaProxy.streamChat(
            request,
            chunk -> writeSseDelta(outputStream, id, request.model(), chunk.content(), chunk.toolCalls())
        );
        writeSse(outputStream, Map.of(
            "id", id,
            "object", "chat.completion.chunk",
            "created", Instant.now().getEpochSecond(),
            "model", model,
            "choices", List.of(Map.of(
                "index", 0,
                "delta", Map.of(),
                "finish_reason", "stop"
            ))
        ));
        outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
        outputStream.flush();
      } catch (Exception ex) {
        log.error("流式聊天请求失败", ex);
        if (!response.isCommitted()) {
          response.reset();
          response.sendError(HttpServletResponse.SC_BAD_GATEWAY, "模型服务暂时不可用");
        } else {
          var outputStream = response.getOutputStream();
          writeSse(outputStream, Map.of("error", Map.of("message", "模型服务暂时不可用")));
          outputStream.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
          outputStream.flush();
        }
      }
      return null;
    }
    try (ComputeQueue.Lease ignored = computeQueue.enterChat()) {
      return ResponseEntity.ok(ollamaProxy.chat(request));
    }
  }

  @PostMapping("/v1/embeddings")
  public Map<String, Object> embeddings(@RequestBody EmbeddingRequest request) {
    try (ComputeQueue.Lease ignored = computeQueue.enterEmbedding()) {
      return ollamaProxy.embeddings(request);
    }
  }

  @GetMapping("/v1/models")
  public Map<String, Object> models() {
    return ollamaProxy.models();
  }

  @GetMapping("/internal/queue/status")
  public Map<String, Object> queueStatus() {
    return computeQueue.status();
  }

  private void writeSseDelta(
      java.io.OutputStream outputStream,
      String id,
      String model,
      String content,
      List<Map<String, Object>> toolCalls
  ) {
    try {
      Map<String, Object> choice = new LinkedHashMap<>();
      choice.put("index", 0);
      Map<String, Object> delta = new LinkedHashMap<>();
      if (content != null && !content.isEmpty()) {
        delta.put("content", content);
      }
      if (toolCalls != null && !toolCalls.isEmpty()) {
        delta.put("tool_calls", toolCalls);
      }
      choice.put("delta", delta);
      choice.put("finish_reason", null);

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("id", id);
      payload.put("object", "chat.completion.chunk");
      payload.put("created", Instant.now().getEpochSecond());
      payload.put("model", model == null ? "" : model);
      payload.put("choices", List.of(choice));
      writeSse(outputStream, payload);
    } catch (Exception ex) {
      throw new IllegalStateException("Failed to write SSE delta", ex);
    }
  }

  private void writeSse(java.io.OutputStream outputStream, Map<String, Object> payload) throws java.io.IOException {
    outputStream.write(("data: " + objectMapper.writeValueAsString(payload) + "\n\n").getBytes(StandardCharsets.UTF_8));
    outputStream.flush();
  }
}
