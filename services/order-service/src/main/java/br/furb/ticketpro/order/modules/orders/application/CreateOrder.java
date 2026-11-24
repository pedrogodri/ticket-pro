package br.furb.ticketpro.order.modules.orders.application;

import br.furb.ticketpro.order.modules.orders.domain.OrderView;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository;
import br.furb.ticketpro.order.platform.db.UserScopedTransaction;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * POST /orders (RFC 0005 §5.3): orders(PENDING) + OrderCreated in one transaction on the app pool
 * (RLS), idempotent per (user, Idempotency-Key). Nothing waits for the saga (R3).
 */
public final class CreateOrder {

  private static final String UNIQUE_VIOLATION = "23505";

  /** The validated request. */
  public record Command(
      UUID userId, UUID idempotencyKey, UUID eventId, UUID sectorId, int quantity) {}

  /** {@code created} is false when the key was already used: the existing order is returned. */
  public record Result(OrderView order, boolean created) {}

  private final DataSource app;
  private final OrderRepository orders;
  private final OrderEvents events;

  public CreateOrder(DataSource app, OrderRepository orders, OrderEvents events) {
    this.app = app;
    this.orders = orders;
    this.events = events;
  }

  public Result execute(Command cmd, String correlationId) throws SQLException {
    try {
      return UserScopedTransaction.run(
          app,
          cmd.userId(),
          c -> {
            var existing = orders.findByIdempotencyKey(c, cmd.userId(), cmd.idempotencyKey());
            if (existing.isPresent()) {
              return new Result(existing.get(), false);
            }
            OrderView order =
                orders.insertPending(
                    c,
                    UUID.randomUUID(),
                    cmd.userId(),
                    cmd.idempotencyKey(),
                    cmd.eventId(),
                    cmd.sectorId(),
                    cmd.quantity());
            events.orderCreated(c, correlationId, order);
            return new Result(order, true);
          });
    } catch (SQLException e) {
      if (!UNIQUE_VIOLATION.equals(e.getSQLState())) {
        throw e;
      }
      // A concurrent request with the same key won: answer with its order.
      return UserScopedTransaction.run(
          app,
          cmd.userId(),
          c ->
              new Result(
                  orders
                      .findByIdempotencyKey(c, cmd.userId(), cmd.idempotencyKey())
                      .orElseThrow(() -> e),
                  false));
    }
  }
}
