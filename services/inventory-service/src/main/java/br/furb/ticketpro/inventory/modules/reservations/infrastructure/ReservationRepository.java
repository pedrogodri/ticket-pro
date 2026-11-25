package br.furb.ticketpro.inventory.modules.reservations.infrastructure;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * JDBC access to {@code sectors}, {@code reservations} and {@code tickets}, always in the caller's
 * transaction (worker pool). Every state change is a conditional UPDATE (RFC 0006 §5.3–5.6).
 */
public final class ReservationRepository {

  /** Price of a sector whose stock was just decremented. */
  public record Price(long unitPriceCents, String currency) {}

  /** Data of a confirmed reservation, to issue its tickets. */
  public record Confirmed(
      UUID reservationId,
      int quantity,
      UUID sectorId,
      String sectorName,
      UUID eventId,
      String eventName,
      Instant eventStartsAt) {}

  /** Step 0: business idempotency, before any effect. */
  public boolean existsByOrderId(Connection c, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT 1 FROM reservations WHERE order_id = ?")) {
      ps.setObject(1, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** R2: atomic conditional decrement. Empty when the sector lacks stock or does not exist. */
  public Optional<Price> decrement(Connection c, UUID sectorId, UUID eventId, int quantity)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE sectors SET available = available - ?"
                + " WHERE id = ? AND event_id = ? AND available >= ?"
                + " RETURNING unit_price_cents, currency")) {
      ps.setInt(1, quantity);
      ps.setObject(2, sectorId);
      ps.setObject(3, eventId);
      ps.setInt(4, quantity);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next()
            ? Optional.of(new Price(rs.getLong(1), rs.getString(2)))
            : Optional.empty();
      }
    }
  }

  public boolean sectorExists(Connection c, UUID sectorId, UUID eventId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT 1 FROM sectors WHERE id = ? AND event_id = ?")) {
      ps.setObject(1, sectorId);
      ps.setObject(2, eventId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next();
      }
    }
  }

  /** Inserts the PENDING reservation; {@code expires_at} comes from the database clock. */
  public Instant insert(
      Connection c,
      UUID id,
      UUID orderId,
      UUID sectorId,
      int quantity,
      long unitPriceCents,
      long totalCents,
      long ttlMillis,
      String channel)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "INSERT INTO reservations (id, order_id, sector_id, quantity, unit_price_cents,"
                + " total_cents, status, expires_at, channel)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'PENDING', now() + (? * interval '1 millisecond'), ?)"
                + " RETURNING expires_at")) {
      ps.setObject(1, id);
      ps.setObject(2, orderId);
      ps.setObject(3, sectorId);
      ps.setInt(4, quantity);
      ps.setLong(5, unitPriceCents);
      ps.setLong(6, totalCents);
      ps.setLong(7, ttlMillis);
      ps.setString(8, channel);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getTimestamp(1).toInstant();
      }
    }
  }

  /** PENDING → CONFIRMED. Empty when there is no PENDING reservation for the order. */
  public Optional<Confirmed> confirm(Connection c, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "UPDATE reservations r SET status = 'CONFIRMED'"
                + " FROM sectors s JOIN events e ON e.id = s.event_id"
                + " WHERE r.order_id = ? AND r.status = 'PENDING' AND s.id = r.sector_id"
                + " RETURNING r.id, r.quantity, s.id, s.name, e.id, e.name, e.starts_at")) {
      ps.setObject(1, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        if (!rs.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new Confirmed(
                rs.getObject(1, UUID.class),
                rs.getInt(2),
                rs.getObject(3, UUID.class),
                rs.getString(4),
                rs.getObject(5, UUID.class),
                rs.getString(6),
                rs.getTimestamp(7).toInstant()));
      }
    }
  }

  /** Current status of the order's reservation, if any (diagnostics only). */
  public Optional<String> statusOf(Connection c, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT status FROM reservations WHERE order_id = ?")) {
      ps.setObject(1, orderId);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
      }
    }
  }

  public void insertTicket(Connection c, UUID id, UUID reservationId, String code)
      throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("INSERT INTO tickets (id, reservation_id, code) VALUES (?, ?, ?)")) {
      ps.setObject(1, id);
      ps.setObject(2, reservationId);
      ps.setString(3, code);
      ps.executeUpdate();
    }
  }

  /**
   * PENDING → RELEASED and returns the stock, in one statement. Only whoever changes the status
   * returns the stock, so it is idempotent and safe against a concurrent expiry (RFC 0006 §5.6).
   */
  public boolean release(Connection c, UUID orderId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "WITH released AS (UPDATE reservations SET status = 'RELEASED'"
                + " WHERE order_id = ? AND status = 'PENDING' RETURNING sector_id, quantity)"
                + " UPDATE sectors s SET available = s.available + r.quantity"
                + " FROM released r WHERE s.id = r.sector_id")) {
      ps.setObject(1, orderId);
      return ps.executeUpdate() > 0;
    }
  }
}
