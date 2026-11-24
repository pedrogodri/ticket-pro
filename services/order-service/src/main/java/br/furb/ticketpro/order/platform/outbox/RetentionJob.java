package br.furb.ticketpro.order.platform.outbox;

import br.furb.ticketpro.order.platform.jobs.PeriodicJob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes, in batches, outbox rows published longer ago than OUTBOX_RETENTION_H and
 * processed_messages older than PROCESSED_MESSAGES_RETENTION_D (ADR-0005, ADR-0006, RFC 0004 §5.7).
 * An unpublished outbox row never matches ({@code published_at IS NULL}). Uses the worker pool;
 * SKIP LOCKED makes it safe in every replica.
 */
public final class RetentionJob implements PeriodicJob {

  private static final Logger log = LoggerFactory.getLogger(RetentionJob.class);

  /** Params: retention in seconds, batch size. */
  static final String PURGE_OUTBOX =
      """
      DELETE FROM outbox WHERE id IN (
        SELECT id FROM outbox
         WHERE published_at < now() - (? * interval '1 second')
         ORDER BY published_at LIMIT ? FOR UPDATE SKIP LOCKED)
      """;

  /** Params: retention in seconds, batch size. */
  static final String PURGE_PROCESSED_MESSAGES =
      """
      DELETE FROM processed_messages WHERE (consumer, message_id) IN (
        SELECT consumer, message_id FROM processed_messages
         WHERE processed_at < now() - (? * interval '1 second')
         ORDER BY processed_at LIMIT ? FOR UPDATE SKIP LOCKED)
      """;

  private final DataSource worker;
  private final Duration outboxRetention;
  private final Duration processedMessagesRetention;
  private final Duration interval;
  private final int batchSize;

  public RetentionJob(
      DataSource worker,
      Duration outboxRetention,
      Duration processedMessagesRetention,
      Duration interval,
      int batchSize) {
    this.worker = worker;
    this.outboxRetention = outboxRetention;
    this.processedMessagesRetention = processedMessagesRetention;
    this.interval = interval;
    this.batchSize = batchSize;
  }

  @Override
  public String name() {
    return "retention";
  }

  @Override
  public Duration interval() {
    return interval;
  }

  @Override
  public int batchSize() {
    return batchSize;
  }

  /**
   * Purges up to {@code batchSize} rows from each table and returns the larger count, so the runner
   * repeats while either table still has a full batch to purge.
   */
  @Override
  public int runOnce() throws SQLException {
    try (Connection c = worker.getConnection()) {
      c.setAutoCommit(false);
      try {
        int outbox = purge(c, PURGE_OUTBOX, outboxRetention);
        int processed = purge(c, PURGE_PROCESSED_MESSAGES, processedMessagesRetention);
        c.commit();
        if (outbox > 0 || processed > 0) {
          log.atInfo()
              .addKeyValue("outbox", outbox)
              .addKeyValue("processedMessages", processed)
              .log("retention_purged");
        }
        return Math.max(outbox, processed);
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }

  private int purge(Connection c, String sql, Duration retention) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setLong(1, retention.toSeconds());
      ps.setInt(2, batchSize);
      return ps.executeUpdate();
    }
  }
}
