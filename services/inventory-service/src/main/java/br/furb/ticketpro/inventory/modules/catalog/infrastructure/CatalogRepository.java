package br.furb.ticketpro.inventory.modules.catalog.infrastructure;

import br.furb.ticketpro.inventory.modules.catalog.domain.EventView;
import br.furb.ticketpro.inventory.modules.catalog.domain.SectorView;
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

/** Read-only JDBC access to {@code events} and {@code sectors} (public catalog, no RLS). */
public final class CatalogRepository {

  /** Keyset position for GET /events (starts_at, id). */
  public record Position(Instant startsAt, UUID id) {}

  /**
   * At most {@code limit} events after {@code after} (null: from the first), by (starts_at, id).
   */
  public List<EventView> page(Connection c, Position after, int limit) throws SQLException {
    String sql =
        "SELECT id, name, starts_at, venue FROM events"
            + (after == null ? "" : " WHERE (starts_at, id) > (?, ?)")
            + " ORDER BY starts_at, id LIMIT ?";
    try (PreparedStatement ps = c.prepareStatement(sql)) {
      int i = 1;
      if (after != null) {
        ps.setTimestamp(i++, Timestamp.from(after.startsAt()));
        ps.setObject(i++, after.id());
      }
      ps.setInt(i, limit);
      List<EventView> items = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          items.add(event(rs));
        }
      }
      return items;
    }
  }

  public Optional<EventView> findEvent(Connection c, UUID id) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement("SELECT id, name, starts_at, venue FROM events WHERE id = ?")) {
      ps.setObject(1, id);
      try (ResultSet rs = ps.executeQuery()) {
        return rs.next() ? Optional.of(event(rs)) : Optional.empty();
      }
    }
  }

  /** Sectors of an event, most expensive first, then by name. */
  public List<SectorView> sectors(Connection c, UUID eventId) throws SQLException {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT id, name, capacity, available, unit_price_cents, currency FROM sectors"
                + " WHERE event_id = ? ORDER BY unit_price_cents DESC, name")) {
      ps.setObject(1, eventId);
      List<SectorView> sectors = new ArrayList<>();
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          sectors.add(
              new SectorView(
                  rs.getObject("id", UUID.class),
                  rs.getString("name"),
                  rs.getInt("capacity"),
                  rs.getInt("available"),
                  rs.getLong("unit_price_cents"),
                  rs.getString("currency")));
        }
      }
      return sectors;
    }
  }

  private static EventView event(ResultSet rs) throws SQLException {
    return new EventView(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getTimestamp("starts_at").toInstant(),
        rs.getString("venue"));
  }
}
