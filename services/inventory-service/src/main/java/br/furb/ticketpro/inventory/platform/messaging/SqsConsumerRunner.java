package br.furb.ticketpro.inventory.platform.messaging;

import br.furb.ticketpro.inventory.platform.tracing.Spans;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the SQS consumer on a virtual thread for the lifetime of the application (RFC 0004 §5.3). On
 * stop the receive loop ends and in-flight messages finish within SHUTDOWN_TIMEOUT_S; a message
 * that is not acknowledged goes back to the queue.
 */
public final class SqsConsumerRunner implements SmartLifecycle {

  private final SqsConsumer consumer;
  private final Duration shutdownTimeout;
  private final AtomicBoolean stop = new AtomicBoolean();
  private volatile Thread thread;

  public SqsConsumerRunner(SqsConsumer consumer, Duration shutdownTimeout) {
    this.consumer = consumer;
    this.shutdownTimeout = shutdownTimeout;
  }

  @Override
  public void start() {
    stop.set(false);
    // Long polls are not traced (Spans.unsampled); each message opens its own consumer span.
    thread =
        Thread.ofVirtual()
            .name("sqs-consumer")
            .start(() -> {
              try (Scope ignored = Spans.unsampled()) {
                consumer.run(stop::get);
              }
            });
  }

  @Override
  public void stop() {
    stop.set(true);
    Thread t = thread;
    if (t != null) {
      try {
        t.join(shutdownTimeout.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    thread = null;
  }

  @Override
  public boolean isRunning() {
    return thread != null;
  }
}
