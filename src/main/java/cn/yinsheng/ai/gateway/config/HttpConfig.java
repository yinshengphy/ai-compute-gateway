package cn.yinsheng.ai.gateway.config;

import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(GatewayProperties.class)
public class HttpConfig {
  @Bean
  RestClient restClient(RestTemplateBuilder builder) {
    return RestClient.builder(builder
        .connectTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofMinutes(5))
        .build()).build();
  }
}
