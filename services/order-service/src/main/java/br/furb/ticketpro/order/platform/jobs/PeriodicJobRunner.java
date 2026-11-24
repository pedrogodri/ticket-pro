package br.furb.ticketpro.order.platform.jobs;

import br.furb.ticketpro.order.platform.retry.Backoff;
import br.furb.ticketpro.order.platform.tracing.Spans;
import io.opentelemetry.context.Scope;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs each {@link PeriodicJob} on its own virtual thread (RFC 0004 §5.7): a full batch runs again
 * at once, otherwise the job sleeps its interval; a failure logs {@code job_failed} and backs off
 * without stopping the process.
 */
public final class PeriodicJobRunner implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(PeriodicJobRunner.class);
  private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(5);

  private final List<PeriodicJob> jobs;
  private final Object signal = new Object();
  private final List<Thread> threads = new ArrayList<>();
  private volatile boolean stopping;

  public PeriodicJobRunner(List<PeriodicJob> jobs) {
    this.jobs = List.copyOf(jobs);
  }

  @Override
  public synchronized void start() {
    stopping = false;
    for (PeriodicJob job : jobs) {
      // Jobs are not traced (Spans.unsampled): their periodic queries would flood Jaeger.
      threads.add(
          Thread.ofVirtual()
              .name("job-" + job.name())
              .start(() -> {
                try (Scope ignored = Spans.unsampled()) {
                  loop(job);
                }
              }));
    }
  }

  @Override
  public synchronized void stop() {
    stopping = true;
    synchronized (signal) {
      signal.notifyAll();
    }
    for (Thread t : threads) {
      try {
        t.join(JOIN_TIMEOUT.toMillis()); // the current batch finishes (one transaction)
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    threads.clear();
  }

  @Override
  public synchronized boolean isRunning() {
    return !threads.isEmpty();
  }

  private void loop(PeriodicJob job) {
    int failures = 0;
    while (!stopping) {
      Duration wait = job.interval();
      try {
        if (job.runOnce() >= job.batchSize()) {
          wait = Duration.ZERO; // full batch: keep draining
        }
        failures = 0;
      } catch (Exception e) {
        log.atWarn()
            .addKeyValue("job", job.name())
            .addKeyValue("error", String.valueOf(e.getMessage()))
            .log("job_failed");
        wait =
            Backoff.delay(
                failures++,
                Backoff.DEFAULT_BASE,
                Backoff.DEFAULT_CAP,
                () -> ThreadLocalRandom.current().nextDouble());
      }
      pause(wait);
    }
  }

  private void pause(Duration wait) {
    if (wait.isZero()) {
      return;
    }
    synchronized (signal) {
      if (stopping) {
        return;
      }
      try {
        signal.wait(Math.max(1, wait.toMillis()));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        stopping = true;
      }
    }
  }
}
