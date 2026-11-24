package br.furb.ticketpro.order.modules.orders.infrastructure;

import br.furb.ticketpro.order.modules.orders.domain.CancelReason;
import br.furb.ticketpro.order.modules.orders.domain.OrderStatus;
import br.furb.ticketpro.order.modules.orders.domain.OrderView;
import br.furb.ticketpro.order.modules.orders.domain.Transitions;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC access to {@code orders}, always in the caller's transaction: the app pool (RLS) for the
 * API, the worker pool for consumers and jobs.
 */
public final class OrderRepository {

  private static final String COLUMNS =
      "id, user_id, event_id, sector_id, quantity, status, cancel_reason, total_cents, currency,"
          + " created_at";

  /** Result of InventoryReserved. */
  public record Reserved(UUID id, UUID userId, long totalCents, String currency) {}

  /** Result of a confirmation. */
  public record Confirmed(
      UUID id,
      UUID userId,
      UUID eventId,
      UUID sectorId,
      int quantity,
      long totalCents,
      String currency,
      Instant confirmedAt) {}

  /** Result of a cancellation. */
  public record Cancelled(UUID id, UUID userId, Instant cancelledAt) {}

  /** Keyset position for GET /orders (created_at DESC, id DESC). */
  public record Position(Instant createdAt, UUID id) {}

  public Optional<OrderView> findByIdempotencyKey(Connection c, UUID userId, UUID key)
      throws SQLException {
    return one(
        c,
        "SELECT " + COLUMNS + " FROM orders WHERE user_id = ? AND idempotency_key = ?",
        userId,
        key);
  }

  public Optional<OrderView> findById(Connection c, UUID userId, UUID id) throws SQLException {
    return one(c, "SELECT " + COLUMNS + " FROM orders WHERE user_id = ? AND id = ?", userId, id);
  }

  /** Inserts a PENDING order; a repeated (user_id, idempotency_key) raises SQLState 23505. */
  public OrderView insertPending(
      Connection c, UUID id, UUID userId, UUID key, UUID eventId, UUID sectorId, int quantity)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO orders (id, user_id, idempotency_key, event_id, sector_id, quantity,"
                + " status) VALUES (?, ?, ?, ?, ?, ?, 'PENDING') RETURNING "
                + COLUMNS)) {
      ps.setObject(1, id);
      ps.setObject(2, userId);
      ps.setObject(3, key);
      ps.setObject(4, eventId);
      ps.setObject(5, sectorId);
      ps.setInt(6, quantity);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return view(rs);
      }
    }
  }

  /** At most {@code limit} orders of the user after {@code after} (null: from the newest). */
  public List<OrderView> page(Connection c, UUID userId, Position after, int limit)
      throws SQLException {
    String sql =
        "SELECT "
            + COLUMNS
            + " FROM orders WHERE user_id = ?"
            + (after == null ? "" : " AND (created_at, id) < (?, ?)")
            + " ORDER BY created_at DESC, id DESC LIMIT ?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      int i = 1;
      ps.setObject(i++, userId);
      if (after != null) {
        ps.setTimestamp(i++, Timestamp.from(after.createdAt()));
        ps.setObject(i++, after.id());
      }
      ps.setInt(i, limit);
      List<OrderView> items = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          items.add(view(rs));
        }
      }
      return items;
    }
  }

  public Optional<Reserved> toAwaitingPayment(
      Connection c, UUID id, long totalCents, String currency, long paymentTimeoutMs)
      throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(Transitions.TO_AWAITING_PAYMENT)) {
      ps.setLong(1, totalCents);
      ps.setString(2, currency);
      ps.setLong(3, paymentTimeoutMs);
      ps.setObject(4, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Reserved(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getLong(3),
                rs.getString(4)));
      }
    }
  }

  public Optional<Confirmed> confirm(Connection c, UUID id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(Transitions.CONFIRM)) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Confirmed(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class),
                rs.getInt(5),
                rs.getLong(6),
                rs.getString(7),
                rs.getTimestamp(8).toInstant()));
      }
    }
  }

  /** Runs one cancelling transition from {@link Transitions} (returns id, user_id, updated_at). */
  public Optional<Cancelled> cancel(Connection c, String transition, UUID id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(transition)) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(cancelled(rs)) : Optional.empty();
      }
    }
  }

  /** Runs a refund_status transition; false when it matched no row. */
  public boolean updateRefund(Connection c, String transition, UUID id) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(transition)) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  public List<Cancelled> cancelOverdue(Connection c, int batchSize) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(Transitions.CANCEL_OVERDUE)) {
      ps.setInt(1, batchSize);
      List<Cancelled> rows = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          rows.add(cancelled(rs));
        }
      }
      return rows;
    }
  }

  private Optional<OrderView> one(Connection c, String sql, UUID a, UUID b) throws SQLException {
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      ps.setObject(1, a);
      ps.setObject(2, b);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(view(rs)) : Optional.empty();
      }
    }
  }

  private static Cancelled cancelled(ResultSet rs) throws SQLException {
    return new Cancelled(
        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getTimestamp(3).toInstant());
  }

  private static OrderView view(ResultSet rs) throws SQLException {
    String reason = rs.getString("cancel_reason");
    long total = rs.getLong("total_cents");
    Long totalCents = rs.wasNull() ? null : total;
    return new OrderView(
        rs.getObject("id", UUID.class),
        rs.getObject("user_id", UUID.class),
        rs.getObject("event_id", UUID.class),
        rs.getObject("sector_id", UUID.class),
        rs.getInt("quantity"),
        OrderStatus.valueOf(rs.getString("status")),
        reason == null ? null : CancelReason.valueOf(reason),
        totalCents,
        rs.getString("currency"),
        rs.getTimestamp("created_at").toInstant());
  }
}
