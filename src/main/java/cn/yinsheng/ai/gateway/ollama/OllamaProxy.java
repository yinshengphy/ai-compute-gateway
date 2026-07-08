package cn.yinsheng.ai.gateway.ollama;

import cn.yinsheng.ai.gateway.config.GatewayProperties;
import cn.yinsheng.ai.gateway.model.ChatCompletionRequest;
import cn.yinsheng.ai.gateway.model.EmbeddingRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OllamaProxy {
  private final GatewayProperties properties;
  private final RestClient restClient;
  private final ObjectMapper objectMapper;

  public OllamaProxy(GatewayProperties properties, RestClient restClient, ObjectMapper objectMapper) {
    this.properties = properties;
    this.restClient = restClient;
    this.objectMapper = objectMapper;
  }

  public Map<String, Object> chat(ChatCompletionRequest request) {
    String model = valueOrDefault(request.model(), properties.defaultChatModel());
    String fallbackModel = fallbackChatModel(model);
    int maxTokens = request.max_tokens() == null ? 1000 : request.max_tokens();
    double temperature = request.temperature() == null ? 0.2 : request.temperature();

    String content;
    try {
      content = chatWithModel(model, request, maxTokens, temperature);
    } catch (Exception ex) {
      if (fallbackModel.equals(model)) {
        throw ex;
      }
      model = fallbackModel;
      content = chatWithModel(model, request, maxTokens, temperature);
    }

    return Map.of(
        "id", "chatcmpl-" + Instant.now().toEpochMilli(),
        "object", "chat.completion",
        "created", Instant.now().getEpochSecond(),
        "model", model,
        "choices", List.of(Map.of(
            "index", 0,
            "message", Map.of("role", "assistant", "content", content),
            "finish_reason", "stop"
        ))
    );
  }

  private String chatWithModel(String model, ChatCompletionRequest request, int maxTokens, double temperature) {
    Map<String, Object> body = Map.of(
        "model", model,
        "stream", false,
        "think", false,
        "keep_alive", properties.chatKeepAlive(),
        "messages", request.messages() == null ? List.of() : request.messages(),
        "options", Map.of("num_predict", maxTokens, "temperature", temperature)
    );

    JsonNode response = restClient.post()
        .uri(properties.ollamaBaseUrl() + "/api/chat")
        .headers(headers -> {
          if (properties.runtimeToken() != null && !properties.runtimeToken().isBlank()) {
            headers.setBearerAuth(properties.runtimeToken());
          }
        })
        .body(body)
        .retrieve()
        .body(JsonNode.class);

    String content = response == null ? "" : response.path("message").path("content").asText();
    if (content == null || content.isBlank()) {
      throw new IllegalStateException("Ollama chat response is empty");
    }
    return content;
  }

  public String streamChat(ChatCompletionRequest request, Consumer<String> deltaConsumer) {
    String model = valueOrDefault(request.model(), properties.defaultChatModel());
    String fallbackModel = fallbackChatModel(model);
    int maxTokens = request.max_tokens() == null ? 1000 : request.max_tokens();
    double temperature = request.temperature() == null ? 0.2 : request.temperature();

    try {
      streamChatWithModel(model, request, maxTokens, temperature, deltaConsumer);
      return model;
    } catch (Exception ex) {
      if (fallbackModel.equals(model)) {
        throw ex;
      }
      streamChatWithModel(fallbackModel, request, maxTokens, temperature, deltaConsumer);
      return fallbackModel;
    }
  }

  private void streamChatWithModel(
      String model,
      ChatCompletionRequest request,
      int maxTokens,
      double temperature,
      Consumer<String> deltaConsumer
  ) {
    AtomicBoolean emittedContent = new AtomicBoolean(false);
    Map<String, Object> body = Map.of(
        "model", model,
        "stream", true,
        "think", false,
        "keep_alive", properties.chatKeepAlive(),
        "messages", request.messages() == null ? List.of() : request.messages(),
        "options", Map.of("num_predict", maxTokens, "temperature", temperature)
    );

    restClient.post()
        .uri(properties.ollamaBaseUrl() + "/api/chat")
        .headers(headers -> {
          if (properties.runtimeToken() != null && !properties.runtimeToken().isBlank()) {
            headers.setBearerAuth(properties.runtimeToken());
          }
        })
        .body(body)
        .exchange((httpRequest, httpResponse) -> {
          try (BufferedReader reader = new BufferedReader(
              new InputStreamReader(httpResponse.getBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
              if (line.isBlank()) {
                continue;
              }
              JsonNode node = objectMapper.readTree(line);
              String content = node.path("message").path("content").asText("");
              if (!content.isEmpty()) {
                emittedContent.set(true);
                deltaConsumer.accept(content);
              }
              if (node.path("done").asBoolean(false)) {
                break;
              }
            }
          }
          return null;
        });
    if (!emittedContent.get()) {
      throw new IllegalStateException("Ollama streaming chat response is empty");
    }
  }

  public Map<String, Object> embeddings(EmbeddingRequest request) {
    String model = valueOrDefault(request.model(), properties.defaultEmbeddingModel());
    String fallbackModel = fallbackEmbeddingModel(model);
    try {
      return embeddingsWithModel(model, request);
    } catch (Exception ex) {
      if (fallbackModel.equals(model)) {
        throw ex;
      }
      return embeddingsWithModel(fallbackModel, request);
    }
  }

  private Map<String, Object> embeddingsWithModel(String model, EmbeddingRequest request) {
    List<String> inputs = normalizeInputs(request.input());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("input", inputs);
    body.put("keep_alive", properties.embeddingKeepAlive());
    if (properties.embeddingNumGpu() != null) {
      body.put("options", Map.of("num_gpu", properties.embeddingNumGpu()));
    }

    JsonNode response = restClient.post()
        .uri(properties.ollamaBaseUrl() + "/api/embed")
        .headers(headers -> {
          if (properties.runtimeToken() != null && !properties.runtimeToken().isBlank()) {
            headers.setBearerAuth(properties.runtimeToken());
          }
        })
        .body(body)
        .retrieve()
        .body(JsonNode.class);

    List<Map<String, Object>> data = new ArrayList<>();
    JsonNode embeddings = response == null ? null : response.path("embeddings");
    if (embeddings != null && embeddings.isArray()) {
      for (int i = 0; i < embeddings.size(); i++) {
        data.add(Map.of("object", "embedding", "index", i, "embedding", embeddings.get(i)));
      }
    }
    return Map.of("object", "list", "model", model, "data", data);
  }

  public Map<String, Object> models() {
    return Map.of("object", "list", "data", List.of(
        Map.of("id", properties.defaultChatModel(), "object", "model"),
        Map.of("id", properties.fallbackChatModel(), "object", "model"),
        Map.of("id", properties.defaultEmbeddingModel(), "object", "model"),
        Map.of("id", properties.fallbackEmbeddingModel(), "object", "model")
    ));
  }

  private String valueOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private String fallbackChatModel(String requestedModel) {
    String fallback = valueOrDefault(properties.fallbackChatModel(), properties.defaultChatModel());
    return requestedModel.equals(fallback) ? requestedModel : fallback;
  }

  private String fallbackEmbeddingModel(String requestedModel) {
    String fallback = valueOrDefault(properties.fallbackEmbeddingModel(), properties.defaultEmbeddingModel());
    return requestedModel.equals(fallback) ? requestedModel : fallback;
  }

  private List<String> normalizeInputs(Object input) {
    if (input instanceof List<?> values) {
      return values.stream().map(String::valueOf).toList();
    }
    if (input == null) {
      return List.of("");
    }
    return List.of(String.valueOf(input));
  }
}
