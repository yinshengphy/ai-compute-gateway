package cn.yinsheng.ai.gateway.remote;

import cn.yinsheng.ai.gateway.config.RemoteChatProperties;
import cn.yinsheng.ai.gateway.model.ChatCompletionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteChatProxyTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private ChatCompletionRequest request(boolean stream) {
    return new ChatCompletionRequest("legacy-local-model", List.of(
        new ChatCompletionRequest.Message("user", "test", null, null, null, null)),
        32, 0.2, stream, null, null);
  }
  private RemoteChatProxy proxy(HttpServer server) {
    return new RemoteChatProxy(new RemoteChatProperties(true,
        "http://127.0.0.1:" + server.getAddress().getPort(), "test", "primary", "backup", 3, 60), mapper);
  }

  @Test
  void fallsBackAndSkipsUnavailablePrimaryDuringCircuit() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<String> models = new ArrayList<>();
    server.createContext("/v1/chat/completions", exchange -> {
      String model = mapper.readTree(exchange.getRequestBody()).path("model").asText();
      models.add(model);
      String body = "backup".equals(model) ? "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}" : "{}";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders("backup".equals(model) ? 200 : 429, bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try {
      RemoteChatProxy proxy = proxy(server);
      assertThat(proxy.chat(request(false)).path("choices").path(0).path("message").path("content").asText()).isEqualTo("OK");
      proxy.chat(request(false));
      assertThat(models).containsExactly("primary", "backup", "backup");
    } finally { server.stop(0); }
  }

  @Test
  void fallsBackForRegionRestrictionButNotInvalidArguments() throws Exception {
    for (String error : List.of("User location is not supported", "invalid messages argument")) {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      List<String> models = new ArrayList<>();
      server.createContext("/v1/chat/completions", exchange -> {
        String model = mapper.readTree(exchange.getRequestBody()).path("model").asText();
        models.add(model);
        byte[] bytes = ("primary".equals(model) ? error
            : "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}").getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders("primary".equals(model) ? 400 : 200, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
      });
      server.start();
      try {
        if (error.startsWith("User location")) {
          proxy(server).chat(request(false));
          assertThat(models).containsExactly("primary", "backup");
        } else {
          assertThatThrownBy(() -> proxy(server).chat(request(false))).isInstanceOf(IllegalStateException.class);
          assertThat(models).containsExactly("primary");
        }
      } finally { server.stop(0); }
    }
  }

  @Test
  void neverFallsBackAfterPublishingContent() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    List<String> models = new ArrayList<>();
    server.createContext("/v1/chat/completions", exchange -> {
      models.add(mapper.readTree(exchange.getRequestBody()).path("model").asText());
      byte[] bytes = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\ndata: {\"error\":{\"message\":\"failed\"}}\n\n".getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try {
      List<String> events = new ArrayList<>();
      assertThatThrownBy(() -> proxy(server).stream(request(true), events::add)).isInstanceOf(IllegalStateException.class);
      assertThat(models).containsExactly("primary");
      assertThat(events).hasSize(1);
    } finally { server.stop(0); }
  }

  @Test
  void fallsBackBeforePublishingAndPreservesToolEvents() throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      String model = mapper.readTree(exchange.getRequestBody()).path("model").asText();
      String body = "primary".equals(model)
          ? "data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata: {\"error\":{}}\n\n"
          : "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"a\",\"function\":{\"name\":\"blog_qa\",\"arguments\":\"{}\"}}]}}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\ndata: [DONE]\n\n";
      byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, bytes.length);
      exchange.getResponseBody().write(bytes); exchange.close();
    });
    server.start();
    try {
      List<String> events = new ArrayList<>();
      proxy(server).stream(request(true), events::add);
      assertThat(String.join("", events)).contains("blog_qa", "[DONE]").doesNotContain("\"role\"");
    } finally { server.stop(0); }
  }
}
