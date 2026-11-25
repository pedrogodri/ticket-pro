package br.furb.ticketpro.inventory.platform.retry;

import java.time.Duration;
import java.util.function.DoubleSupplier;

/** Exponential backoff with full jitter (RFC 0003 §5.2). */
public final class Backoff {

  public static final Duration DEFAULT_BASE = Duration.ofMillis(200);
  public static final Duration DEFAULT_CAP = Duration.ofSeconds(30);

  private Backoff() {}

  /** random(0, min(cap, base·2^attempt)) with {@code rnd} in [0,1); attempt starts at 0. */
  public static Duration delay(int attempt, Duration base, Duration cap, DoubleSupplier rnd) {
    int n = Math.max(0, attempt);
    long limit = cap.toNanos();
    // base·2^attempt overflows quickly; stop doubling once the cap is reached.
    if (n < 62) {
      long d = base.toNanos() << n;
      if (d > 0 && d < limit && d >> n == base.toNanos()) {
        limit = d;
      }
    }
    return Duration.ofNanos((long) (rnd.getAsDouble() * limit));
  }
}
