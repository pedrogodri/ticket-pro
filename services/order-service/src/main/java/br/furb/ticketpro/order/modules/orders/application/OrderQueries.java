package br.furb.ticketpro.order.modules.orders.application;

import br.furb.ticketpro.order.modules.orders.domain.OrderView;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository.Position;
import br.furb.ticketpro.order.platform.db.UserScopedTransaction;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * GET /orders/{id} and GET /orders on the app pool. The SQL filters by user_id and RLS is the
 * second barrier (BOLA, RFC 0002 §5.6).
 */
public final class OrderQueries {

  /** One page; {@code next} is null on the last page. */
  public record Page(List<OrderView> items, Position next) {}

  private final DataSource app;
  private final OrderRepository orders;

  public OrderQueries(DataSource app, OrderRepository orders) {
    this.app = app;
    this.orders = orders;
  }

  public Optional<OrderView> get(UUID userId, UUID orderId) throws SQLException {
    return UserScopedTransaction.run(app, userId, c -> orders.findById(c, userId, orderId));
  }

  public Page list(UUID userId, Position after, int limit) throws SQLException {
    List<OrderView> rows =
        UserScopedTransaction.run(app, userId, c -> orders.page(c, userId, after, limit + 1));
    if (rows.size() <= limit) {
      return new Page(rows, null);
    }
    List<OrderView> items = rows.subList(0, limit);
    OrderView last = items.get(limit - 1);
    return new Page(List.copyOf(items), new Position(last.createdAt(), last.id()));
  }
}
