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
import java.util.concurrent.atomic.AtomicInteger;
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

    JsonNode message;
    try {
      message = chatWithModel(model, request, maxTokens, temperature);
    } catch (Exception ex) {
      if (fallbackModel.equals(model) || containsImages(request)) {
        throw ex;
      }
      model = fallbackModel;
      message = chatWithModel(model, request, maxTokens, temperature);
    }

    return Map.of(
        "id", "chatcmpl-" + Instant.now().toEpochMilli(),
        "object", "chat.completion",
        "created", Instant.now().getEpochSecond(),
        "model", model,
        "choices", List.of(Map.of(
            "index", 0,
            "message", openAiAssistantMessage(message),
            "finish_reason", "stop"
        ))
    );
  }

  private JsonNode chatWithModel(String model, ChatCompletionRequest request, int maxTokens, double temperature) {
    Map<String, Object> body = chatBody(model, request, false, maxTokens, temperature);

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

    JsonNode message = response == null ? null : response.path("message");
    boolean hasContent = message != null && !message.path("content").asText("").isBlank();
    boolean hasTools = message != null && message.path("tool_calls").isArray() && !message.path("tool_calls").isEmpty();
    if (!hasContent && !hasTools) {
      throw new IllegalStateException("Ollama chat response is empty");
    }
    return message;
  }

  public String streamChat(ChatCompletionRequest request, Consumer<StreamChunk> chunkConsumer) {
    String model = valueOrDefault(request.model(), properties.defaultChatModel());
    String fallbackModel = fallbackChatModel(model);
    int maxTokens = request.max_tokens() == null ? 1000 : request.max_tokens();
    double temperature = request.temperature() == null ? 0.2 : request.temperature();

    try {
      streamChatWithModel(model, request, maxTokens, temperature, chunkConsumer);
      return model;
    } catch (Exception ex) {
      if (fallbackModel.equals(model) || containsImages(request)) {
        throw ex;
      }
      streamChatWithModel(fallbackModel, request, maxTokens, temperature, chunkConsumer);
      return fallbackModel;
    }
  }

  private void streamChatWithModel(
      String model,
      ChatCompletionRequest request,
      int maxTokens,
      double temperature,
      Consumer<StreamChunk> chunkConsumer
  ) {
    AtomicBoolean emittedContent = new AtomicBoolean(false);
    Map<String, Object> body = chatBody(model, request, true, maxTokens, temperature);

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
              JsonNode message = node.path("message");
              String content = message.path("content").asText("");
              List<Map<String, Object>> toolCalls = openAiToolCalls(message.path("tool_calls"));
              if (!content.isEmpty()) {
                emittedContent.set(true);
              }
              if (!content.isEmpty() || !toolCalls.isEmpty()) {
                emittedContent.set(true);
                chunkConsumer.accept(new StreamChunk(content, toolCalls));
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

  private Map<String, Object> chatBody(
      String model,
      ChatCompletionRequest request,
      boolean stream,
      int maxTokens,
      double temperature
  ) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("model", model);
    body.put("stream", stream);
    body.put("think", false);
    body.put("keep_alive", properties.chatKeepAlive());
    body.put("messages", ollamaMessages(request.messages()));
    body.put("options", Map.of("num_predict", maxTokens, "temperature", temperature));
    if (request.tools() != null && !request.tools().isEmpty()) {
      body.put("tools", request.tools());
    }
    return body;
  }

  private List<Map<String, Object>> ollamaMessages(List<ChatCompletionRequest.Message> messages) {
    if (messages == null) {
      return List.of();
    }
    return messages.stream().map(this::ollamaMessage).toList();
  }

  private boolean containsImages(ChatCompletionRequest request) {
    if (request.messages() == null) return false;
    for (ChatCompletionRequest.Message message : request.messages()) {
      if (message.images() != null && !message.images().isEmpty()) return true;
      if (!(message.content() instanceof List<?> parts)) continue;
      for (Object part : parts) {
        if (part instanceof Map<?, ?> map && "image_url".equals(String.valueOf(map.get("type")))) return true;
      }
    }
    return false;
  }

  private Map<String, Object> ollamaMessage(ChatCompletionRequest.Message message) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("role", message.role());
    MessageContent content = normalizeContent(message.content(), message.images());
    value.put("content", content.text());
    if (!content.images().isEmpty()) {
      value.put("images", content.images());
    }
    if (message.tool_calls() != null && !message.tool_calls().isEmpty()) {
      value.put("tool_calls", ollamaToolCalls(message.tool_calls()));
    }
    if (message.name() != null && !message.name().isBlank()) {
      value.put("tool_name", message.name());
    }
    return value;
  }

  private List<Map<String, Object>> ollamaToolCalls(List<Map<String, Object>> calls) {
    List<Map<String, Object>> converted = new ArrayList<>();
    for (Map<String, Object> call : calls) {
      Object rawFunction = call.get("function");
      if (!(rawFunction instanceof Map<?, ?> function)) {
        continue;
      }
      Object rawName = function.get("name");
      String name = rawName == null ? "" : String.valueOf(rawName);
      Object arguments = function.get("arguments");
      if (arguments instanceof String json) {
        try {
          arguments = objectMapper.readValue(json, Map.class);
        } catch (Exception ex) {
          arguments = Map.of();
        }
      }
      converted.add(Map.of("function", Map.of(
          "name", name,
          "arguments", arguments == null ? Map.of() : arguments
      )));
    }
    return converted;
  }

  private MessageContent normalizeContent(Object rawContent, List<String> directImages) {
    StringBuilder text = new StringBuilder();
    List<String> images = new ArrayList<>();
    if (directImages != null) {
      directImages.stream().map(this::stripDataUrl).filter(value -> !value.isBlank()).forEach(images::add);
    }
    JsonNode content = objectMapper.valueToTree(rawContent);
    if (content.isTextual()) {
      text.append(content.asText());
    } else if (content.isArray()) {
      for (JsonNode part : content) {
        String type = part.path("type").asText();
        if ("text".equals(type)) {
          text.append(part.path("text").asText());
        } else if ("image_url".equals(type)) {
          String image = stripDataUrl(part.path("image_url").path("url").asText());
          if (!image.isBlank()) {
            images.add(image);
          }
        }
      }
    }
    return new MessageContent(text.toString(), images);
  }

  private String stripDataUrl(String value) {
    if (value == null) {
      return "";
    }
    int marker = value.indexOf(";base64,");
    return marker >= 0 ? value.substring(marker + ";base64,".length()) : value;
  }

  private Map<String, Object> openAiAssistantMessage(JsonNode message) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("role", "assistant");
    value.put("content", message.path("content").asText(""));
    List<Map<String, Object>> converted = openAiToolCalls(message.path("tool_calls"));
    if (!converted.isEmpty()) {
      value.put("tool_calls", converted);
    }
    return value;
  }

  private List<Map<String, Object>> openAiToolCalls(JsonNode calls) {
    if (!calls.isArray() || calls.isEmpty()) {
      return List.of();
    }
    AtomicInteger sequence = new AtomicInteger();
    List<Map<String, Object>> converted = new ArrayList<>();
    for (JsonNode call : calls) {
        JsonNode function = call.path("function");
        Object arguments = objectMapper.convertValue(function.path("arguments"), Object.class);
        String argumentsJson;
        try {
          argumentsJson = arguments instanceof String string ? string : objectMapper.writeValueAsString(arguments);
        } catch (Exception ex) {
          argumentsJson = "{}";
        }
        converted.add(Map.of(
            "id", "call_" + Instant.now().toEpochMilli() + "_" + sequence.incrementAndGet(),
            "type", "function",
            "function", Map.of(
                "name", function.path("name").asText(""),
                "arguments", argumentsJson
            )
        ));
    }
    return converted;
  }

  private record MessageContent(String text, List<String> images) {
  }

  public record StreamChunk(String content, List<Map<String, Object>> toolCalls) {
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
