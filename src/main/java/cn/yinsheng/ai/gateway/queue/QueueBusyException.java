package cn.yinsheng.ai.gateway.queue;

public class QueueBusyException extends RuntimeException {
  public QueueBusyException(String message) {
    super(message);
  }
}
