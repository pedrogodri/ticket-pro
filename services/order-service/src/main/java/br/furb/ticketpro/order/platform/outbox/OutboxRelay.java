package br.furb.ticketpro.order.platform.outbox;

import br.furb.ticketpro.order.platform.envelope.Envelope;
import br.furb.ticketpro.order.platform.retry.Backoff;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * Publishes pending outbox rows (ADR-0005, RFC 0003 §5.3). Safe in every replica (SKIP LOCKED +
 * lease).
 */
public final class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final DataSource dataSource;
  private final OutboxPublisher publisher;
  private final OutboxRelayProperties props;
  private final AtomicBoolean brokerUp = new AtomicBoolean(true);

  public OutboxRelay(
      DataSource dataSource, OutboxPublisher publisher, OutboxRelayProperties props) {
    this.dataSource = dataSource;
    this.publisher = publisher;
    this.props = props;
  }

  /** Whether the last publish attempt succeeded (readiness: broker up|degraded). */
  public boolean brokerUp() {
    return brokerUp.get();
  }

  private record Row(
      long id, String topicArn, String traceparent, int attempts, Envelope envelope) {}

  /**
   * Claims one batch and publishes it in id order. On the first publish failure the row is
   * rescheduled with backoff, the rest of the batch is released, and the failure is rethrown.
   */
  public int runOnce() throws SQLException {
    long started = System.nanoTime();
    List<Row> rows = claim();
    int published = 0;
    for (int i = 0; i < rows.size(); i++) {
      // Stop at half the lease: past it another relay may re-claim rows still being published.
      if (System.nanoTime() - started >= props.claimLease().toNanos() / 2) {
        release(rows.subList(i, rows.size()));
        return published;
      }
      Row row = rows.get(i);
      try {
        publisher.publish(row.topicArn(), row.envelope(), Optional.ofNullable(row.traceparent()));
      } catch (RuntimeException e) {
        setBroker(false, e);
        reschedule(row, e);
        release(rows.subList(i + 1, rows.size()));
        throw e;
      }
      try {
        execute("UPDATE outbox SET published_at = now(), last_error = NULL WHERE id = ?", row.id());
      } catch (SQLException e) {
        // The row stays leased and is republished later (at-least-once); release the rest now.
        release(rows.subList(i + 1, rows.size()));
        throw e;
      }
      setBroker(true, null);
      published++;
      Envelope e = row.envelope();
      log.atInfo()
          .addKeyValue("messageId", e.messageId())
          .addKeyValue("type", e.type())
          .addKeyValue("version", e.version())
          .addKeyValue("correlationId", e.correlationId())
          .log("message_published");
    }
    return published;
  }

  /** Publishes until {@code stop} is true: pollInterval between batches, backoff after failures. */
  public void run(BooleanSupplier stop) {
    int failures = 0;
    while (!stop.getAsBoolean()) {
      long waitMs = props.pollInterval().toMillis();
      try {
        int published = runOnce();
        failures = 0;
        if (published == props.batchSize()) {
          waitMs = 0; // full batch: keep draining
        }
      } catch (SQLException | RuntimeException e) {
        log.atWarn().addKeyValue("error", e.getMessage()).log("outbox_relay_error");
        waitMs =
            Backoff.delay(
                    failures++,
                    Backoff.DEFAULT_BASE,
                    props.maxBackoff(),
                    () -> ThreadLocalRandom.current().nextDouble())
                .toMillis();
      }
      try {
        Thread.sleep(waitMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  private List<Row> claim() throws SQLException {
    String sql =
        """
        UPDATE outbox SET next_attempt_at = now() + (? * interval '1 millisecond')
        WHERE id IN (
            SELECT id FROM outbox
            WHERE published_at IS NULL AND next_attempt_at <= now()
            ORDER BY id LIMIT ?
            FOR UPDATE SKIP LOCKED)
        RETURNING id, message_id::text, topic_arn, type, version, correlation_id::text, traceparent,
                  payload::text, occurred_at, attempts
        """;
    List<Row> rows = new ArrayList<>();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, props.claimLease().toMillis());
      ps.setInt(2, props.batchSize());
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          Envelope env =
              new Envelope(
                  rs.getString(2),
                  rs.getString(6),
                  rs.getString(4),
                  rs.getInt(5),
                  rs.getTimestamp(9).toInstant(),
                  JSON.readTree(rs.getString(8)));
          rows.add(new Row(rs.getLong(1), rs.getString(3), rs.getString(7), rs.getInt(10), env));
        }
      }
    }
    rows.sort(Comparator.comparingLong(Row::id)); // RETURNING has no order
    return rows;
  }

  private void reschedule(Row row, RuntimeException cause) throws SQLException {
    long delayMs =
        Backoff.delay(
                row.attempts() + 1,
                Backoff.DEFAULT_BASE,
                props.maxBackoff(),
                () -> ThreadLocalRandom.current().nextDouble())
            .toMillis();
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement(
                "UPDATE outbox SET attempts = attempts + 1, last_error = ?,"
                    + " next_attempt_at = now() + (? * interval '1 millisecond') WHERE id = ?")) {
      ps.setString(1, String.valueOf(cause.getMessage()));
      ps.setLong(2, delayMs);
      ps.setLong(3, row.id());
      ps.executeUpdate();
    }
  }

  private void release(List<Row> rows) throws SQLException {
    if (rows.isEmpty()) {
      return;
    }
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps =
            c.prepareStatement("UPDATE outbox SET next_attempt_at = now() WHERE id = ANY(?)")) {
      Array ids = c.createArrayOf("bigint", rows.stream().map(Row::id).toArray());
      ps.setArray(1, ids);
      ps.executeUpdate();
    }
  }

  private void execute(String sql, long id) throws SQLException {
    try (Connection c = dataSource.getConnection();
        PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, id);
      ps.executeUpdate();
    }
  }

  // One log line per broker state transition (RFC 0003 §5.3).
  private void setBroker(boolean up, RuntimeException cause) {
    if (brokerUp.getAndSet(up) == up) {
      return;
    }
    if (up) {
      log.info("broker_up");
    } else {
      log.atWarn().addKeyValue("error", cause.getMessage()).log("broker_down");
    }
  }
}
