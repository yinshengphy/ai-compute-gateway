package cn.yinsheng.ai.gateway.queue;

import cn.yinsheng.ai.gateway.config.GatewayProperties;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

@Component
public class ComputeQueue {
  private final GatewayProperties properties;
  private final Semaphore chatSemaphore;
  private final Semaphore runtimeSemaphore;
  private final AtomicInteger waitingChats = new AtomicInteger();
  private final AtomicInteger waitingEmbeddings = new AtomicInteger();

  public ComputeQueue(GatewayProperties properties) {
    this.properties = properties;
    this.chatSemaphore = new Semaphore(Math.max(1, properties.chatConcurrency()));
    this.runtimeSemaphore = new Semaphore(Math.max(1, properties.embeddingConcurrency()));
  }

  public Lease enterChat() {
    waitingChats.incrementAndGet();
    try {
      return acquire(chatSemaphore);
    } finally {
      waitingChats.decrementAndGet();
    }
  }

  public Lease enterEmbedding() {
    waitingEmbeddings.incrementAndGet();
    try {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(properties.queueTimeoutMs());
      while (System.nanoTime() < deadline) {
        if (runtimeSemaphore.tryAcquire(200, TimeUnit.MILLISECONDS)) {
          return new Lease(runtimeSemaphore);
        }
      }
      throw new QueueBusyException("当前请求较多，请稍后再试。");
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new QueueBusyException("请求已中断，请稍后再试。");
    } finally {
      waitingEmbeddings.decrementAndGet();
    }
  }

  public Map<String, Object> status() {
    return Map.of(
        "chatAvailable", chatSemaphore.availablePermits(),
        "runtimeAvailable", runtimeSemaphore.availablePermits(),
        "waitingChats", waitingChats.get(),
        "waitingEmbeddings", waitingEmbeddings.get()
    );
  }

  private Lease acquire(Semaphore semaphore) {
    try {
      boolean acquired = semaphore.tryAcquire(properties.queueTimeoutMs(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new QueueBusyException("当前请求较多，请稍后再试。");
      }
      return new Lease(semaphore);
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
      throw new QueueBusyException("请求已中断，请稍后再试。");
    }
  }

  public static class Lease implements AutoCloseable {
    private final Semaphore semaphore;
    private boolean closed;

    private Lease(Semaphore semaphore) {
      this.semaphore = semaphore;
    }

    @Override
    public void close() {
      if (!closed) {
        semaphore.release();
        closed = true;
      }
    }
  }

}
