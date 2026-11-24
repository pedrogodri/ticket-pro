package br.furb.ticketpro.order.modules.orders.application;

import br.furb.ticketpro.order.modules.orders.domain.CancelReason;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository.Cancelled;
import br.furb.ticketpro.order.platform.jobs.PeriodicJob;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cancels AWAITING_PAYMENT orders past their deadline as PAYMENT_TIMEOUT and publishes
 * OrderCancelled in the same transaction (RFC 0005 §5.5, F1/F7). Uses the worker pool; SKIP LOCKED
 * makes it safe in every replica.
 */
public final class DeadlineScanner implements PeriodicJob {

  private static final Logger log = LoggerFactory.getLogger(DeadlineScanner.class);

  private final DataSource worker;
  private final OrderRepository orders;
  private final OrderEvents events;
  private final Duration interval;
  private final int batchSize;

  public DeadlineScanner(
      DataSource worker,
      OrderRepository orders,
      OrderEvents events,
      Duration interval,
      int batchSize) {
    this.worker = worker;
    this.orders = orders;
    this.events = events;
    this.interval = interval;
    this.batchSize = batchSize;
  }

  @Override
  public String name() {
    return "deadline-scanner";
  }

  @Override
  public Duration interval() {
    return interval;
  }

  @Override
  public int batchSize() {
    return batchSize;
  }

  @Override
  public int runOnce() throws SQLException {
    try (Connection c = worker.getConnection()) {
      c.setAutoCommit(false);
      try {
        List<Cancelled> rows = orders.cancelOverdue(c, batchSize);
        for (Cancelled row : rows) {
          // RFC 0005 decision 5: orders keeps no correlation_id, so the timeout starts a new one.
          events.orderCancelled(c, UUID.randomUUID().toString(), row, CancelReason.PAYMENT_TIMEOUT);
        }
        c.commit();
        for (Cancelled row : rows) {
          log.atInfo()
              .addKeyValue("orderId", row.id().toString())
              .addKeyValue("reason", CancelReason.PAYMENT_TIMEOUT.name())
              .log("compensation_applied");
        }
        return rows.size();
      } catch (SQLException | RuntimeException e) {
        c.rollback();
        throw e;
      }
    }
  }
}
