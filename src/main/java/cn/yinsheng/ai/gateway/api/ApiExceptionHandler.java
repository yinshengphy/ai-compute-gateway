package cn.yinsheng.ai.gateway.api;

import cn.yinsheng.ai.gateway.queue.QueueBusyException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(QueueBusyException.class)
  public ResponseEntity<Map<String, Object>> handleBusy(QueueBusyException ex) {
    return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
        .body(Map.of("error", Map.of("message", ex.getMessage(), "type", "queue_busy")));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleError(Exception ex) {
    log.error("AI compute gateway request failed", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(Map.of("error", Map.of("message", "AI 计算服务暂时不可用。", "type", "gateway_error")));
  }
}
