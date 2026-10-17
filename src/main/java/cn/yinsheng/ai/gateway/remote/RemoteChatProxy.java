package cn.yinsheng.ai.gateway.remote;

import cn.yinsheng.ai.gateway.config.RemoteChatProperties;
import cn.yinsheng.ai.gateway.model.ChatCompletionRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RemoteChatProxy {
  private static final Logger log = LoggerFactory.getLogger(RemoteChatProxy.class);
  private final RemoteChatProperties properties;
  private final ObjectMapper mapper;
  private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
  private final AtomicLong primaryUnavailableUntil = new AtomicLong();

  public RemoteChatProxy(RemoteChatProperties properties, ObjectMapper mapper) {
    this.properties = properties;
    this.mapper = mapper;
  }

  public boolean enabled() { return properties.enabled(); }

  public JsonNode chat(ChatCompletionRequest request) {
    return execute(request, null);
  }

  public void stream(ChatCompletionRequest request, Consumer<String> eventConsumer) {
    execute(request, eventConsumer);
  }

  public Map<String, Object> models() {
    return Map.of("object", "list", "data", List.of(
        Map.of("id", properties.primaryModel(), "object", "model"),
        Map.of("id", properties.fallbackModel(), "object", "model"),
        Map.of("id", "bge-m3", "object", "model")));
  }

  private JsonNode execute(ChatCompletionRequest request, Consumer<String> consumer) {
    boolean skipPrimary = System.currentTimeMillis() < primaryUnavailableUntil.get();
    List<String> models = skipPrimary ? List.of(properties.fallbackModel())
        : List.of(properties.primaryModel(), properties.fallbackModel());
    RuntimeException failure = null;
    for (String model : models) {
      boolean[] published = {false};
      long started = System.nanoTime();
      try {
        JsonNode result = call(request, model, consumer, published);
        if (model.equals(properties.primaryModel())) primaryUnavailableUntil.set(0);
        log.info("远程聊天 model={} fallback={} latencyMs={}", model,
            !model.equals(properties.primaryModel()), (System.nanoTime() - started) / 1_000_000);
        return result;
      } catch (Exception ex) {
        boolean retryable = !(ex instanceof UpstreamException up) || up.retryable;
        log.warn("远程聊天失败 model={} published={} retryable={} errorType={}",
            model, published[0], retryable, ex.getClass().getSimpleName());
        if (ex instanceof InterruptedException) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("模型请求已中断");
        }
        failure = new IllegalStateException("远程模型暂时不可用", ex);
        if (published[0] || !retryable) throw failure;
        if (model.equals(properties.primaryModel())) {
          primaryUnavailableUntil.set(System.currentTimeMillis() + properties.circuitSeconds() * 1000L);
        }
      }
    }
    throw failure == null ? new IllegalStateException("未配置远程模型") : failure;
  }

  private JsonNode call(ChatCompletionRequest request, String model, Consumer<String> consumer,
      boolean[] published) throws Exception {
    Map<String, Object> body = mapper.convertValue(request, Map.class);
    body = new LinkedHashMap<>(body);
    body.values().removeIf(java.util.Objects::isNull);
    body.put("model", model);
    body.put("stream", consumer != null);
    HttpRequest upstream = HttpRequest.newBuilder()
        .uri(URI.create(properties.baseUrl() + "/v1/chat/completions"))
        .timeout(Duration.ofSeconds(properties.timeoutSeconds()))
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + properties.apiToken())
        .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
    var response = client.send(upstream, HttpResponse.BodyHandlers.ofInputStream());
    var watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "remote-chat-deadline"); t.setDaemon(true); return t;
    });
    watchdog.schedule(() -> { try { response.body().close(); } catch (Exception ignored) { } },
        properties.timeoutSeconds(), TimeUnit.SECONDS);
    try (var input = response.body()) {
      if (response.statusCode() / 100 != 2) {
        int status = response.statusCode();
        throw new UpstreamException(status == 401 || status == 403 || status == 408
            || status == 429 || status >= 500);
      }
      if (consumer == null) {
        JsonNode result = mapper.readTree(input);
        JsonNode message = result.path("choices").path(0).path("message");
        if (result.has("error") || (message.path("content").asText("").isBlank()
            && message.path("tool_calls").isEmpty())) throw new UpstreamException(true);
        return result;
      }
      StringBuilder pending = new StringBuilder();
      boolean completed = false;
      try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
        String line;
        while ((line = reader.readLine()) != null) {
          if (!line.startsWith("data:")) continue;
          String data = line.substring(5).trim();
          if (data.isEmpty()) continue;
          if ("[DONE]".equals(data)) { completed = true; break; }
          JsonNode node = mapper.readTree(data);
          if (node.has("error")) throw new UpstreamException(true);
          JsonNode choice = node.path("choices").path(0);
          JsonNode delta = choice.path("delta");
          String event = "data: " + data + "\n\n";
          if (!published[0]) {
            pending.append(event);
            if (pending.length() > 65536) throw new UpstreamException(true);
            if (!delta.path("content").asText("").isEmpty() || !delta.path("tool_calls").isEmpty()) {
              published[0] = true;
              consumer.accept(pending.toString());
              pending.setLength(0);
            }
          } else consumer.accept(event);
          if (!choice.path("finish_reason").isNull() && !choice.path("finish_reason").isMissingNode()) {
            completed = true;
          }
        }
      }
      if (!completed || !published[0]) throw new UpstreamException(true);
      consumer.accept("data: [DONE]\n\n");
      return null;
    } finally { watchdog.shutdownNow(); }
  }

  private static final class UpstreamException extends RuntimeException {
    private final boolean retryable;
    private UpstreamException(boolean retryable) { super("上游模型请求失败"); this.retryable = retryable; }
  }
}
