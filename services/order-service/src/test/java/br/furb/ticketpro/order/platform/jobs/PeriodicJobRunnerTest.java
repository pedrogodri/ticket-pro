package br.furb.ticketpro.order.platform.jobs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** p20: the runner survives a failing job, drains full batches and stops cleanly. */
class PeriodicJobRunnerTest {

  /** Fails on the first call, then returns two full batches and a partial one. */
  private static final class ScriptedJob implements PeriodicJob {
    final AtomicInteger calls = new AtomicInteger();

    @Override
    public String name() {
      return "scripted";
    }

    @Override
    public Duration interval() {
      return Duration.ofHours(1); // only full batches and backoff can run it again
    }

    @Override
    public int batchSize() {
      return 10;
    }

    @Override
    public int runOnce() {
      int n = calls.incrementAndGet();
      if (n == 1) {
        throw new IllegalStateException("database down");
      }
      return n <= 3 ? 10 : 3;
    }
  }

  @Test
  void p20SurvivesFailureDrainsFullBatchesAndStops() throws Exception {
    ScriptedJob job = new ScriptedJob();
    PeriodicJobRunner runner = new PeriodicJobRunner(List.of(job));
    runner.start();
    Instant deadline = Instant.now().plusSeconds(10);
    while (job.calls.get() < 4) {
      assertThat(Instant.now()).as("job retried after failure and drained").isBefore(deadline);
      Thread.sleep(20);
    }
    Thread.sleep(200);
    assertThat(job.calls.get()).as("partial batch waits the interval").isEqualTo(4);
    assertThat(runner.isRunning()).isTrue();

    long started = System.nanoTime();
    runner.stop();
    assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
    assertThat(runner.isRunning()).isFalse();
  }
}
