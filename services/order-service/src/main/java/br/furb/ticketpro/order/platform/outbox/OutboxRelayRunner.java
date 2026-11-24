package br.furb.ticketpro.order.platform.outbox;

import br.furb.ticketpro.order.platform.tracing.Spans;
import io.opentelemetry.context.Scope;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

/** Runs the outbox relay on a virtual thread for the lifetime of the application (ADR-0005). */
public final class OutboxRelayRunner implements SmartLifecycle {

  private final OutboxRelay relay;
  private final AtomicBoolean stop = new AtomicBoolean();
  private volatile Thread thread;

  public OutboxRelayRunner(OutboxRelay relay) {
    this.relay = relay;
  }

  @Override
  public void start() {
    stop.set(false);
    // Polling is not traced (Spans.unsampled); each publication opens its own producer span.
    thread =
        Thread.ofVirtual()
            .name("outbox-relay")
            .start(() -> {
              try (Scope ignored = Spans.unsampled()) {
                relay.run(stop::get);
              }
            });
  }

  @Override
  public void stop() {
    stop.set(true);
    Thread t = thread;
    if (t != null) {
      try {
        t.join(5000); // the current batch finishes; unpublished rows stay in the outbox
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
