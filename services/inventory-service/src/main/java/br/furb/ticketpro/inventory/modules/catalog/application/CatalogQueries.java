package br.furb.ticketpro.inventory.modules.catalog.application;

import br.furb.ticketpro.inventory.modules.catalog.domain.EventDetail;
import br.furb.ticketpro.inventory.modules.catalog.domain.EventView;
import br.furb.ticketpro.inventory.modules.catalog.infrastructure.CatalogRepository;
import br.furb.ticketpro.inventory.modules.catalog.infrastructure.CatalogRepository.Position;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

/** GET /events and GET /events/{id} on the app pool (RFC 0006 §5.2). */
public final class CatalogQueries {

  /** One page; {@code next} is null on the last page. */
  public record Page(List<EventView> items, Position next) {}

  private final DataSource app;
  private final CatalogRepository catalog;

  public CatalogQueries(DataSource app, CatalogRepository catalog) {
    this.app = app;
    this.catalog = catalog;
  }

  public Page list(Position after, int limit) throws SQLException {
    try (Connection c = app.getConnection()) {
      List<EventView> rows = catalog.page(c, after, limit + 1);
      if (rows.size() <= limit) {
        return new Page(rows, null);
      }
      List<EventView> items = List.copyOf(rows.subList(0, limit));
      EventView last = items.get(limit - 1);
      return new Page(items, new Position(last.startsAt(), last.id()));
    }
  }

  public Optional<EventDetail> get(UUID id) throws SQLException {
    try (Connection c = app.getConnection()) {
      Optional<EventView> event = catalog.findEvent(c, id);
      if (event.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(new EventDetail(event.get(), catalog.sectors(c, id)));
    }
  }
}
