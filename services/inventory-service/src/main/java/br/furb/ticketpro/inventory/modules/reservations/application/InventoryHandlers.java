package br.furb.ticketpro.inventory.modules.reservations.application;

import br.furb.ticketpro.inventory.modules.reservations.domain.OrderCreatedV2;
import br.furb.ticketpro.inventory.modules.reservations.domain.Upcaster;
import br.furb.ticketpro.inventory.modules.reservations.infrastructure.ReservationRepository;
import br.furb.ticketpro.inventory.modules.reservations.infrastructure.ReservationRepository.Confirmed;
import br.furb.ticketpro.inventory.modules.reservations.infrastructure.ReservationRepository.Price;
import br.furb.ticketpro.inventory.platform.envelope.Envelope;
import br.furb.ticketpro.inventory.platform.envelope.InvalidMessageException;
import br.furb.ticketpro.inventory.platform.envelope.StaleEventException;
import br.furb.ticketpro.inventory.platform.messaging.MessageHandler;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The inventory's consumers (RFC 0006 §5.3–5.6), each running in the consumer's transaction on the
 * worker pool: reserve stock on OrderCreated (v1 and v2), issue tickets on OrderConfirmed and
 * return stock on OrderCancelled. A handler never swallows an exception to commit: the rollback
 * undoes the stock decrement.
 */
public final class InventoryHandlers {

  private static final Logger log = LoggerFactory.getLogger(InventoryHandlers.class);

  private final ReservationRepository reservations;
  private final InventoryEvents events;
  private final Duration reservationTtl;

  public InventoryHandlers(
      ReservationRepository reservations, InventoryEvents events, Duration reservationTtl) {
    this.reservations = reservations;
    this.events = events;
    this.reservationTtl = reservationTtl;
  }

  /** Handlers by event type, for the SqsConsumer. */
  public Map<String, MessageHandler> all() {
    return Map.of(
        "OrderCreated", handler(Set.of(1, 2), this::orderCreated),
        "OrderConfirmed", handler(Set.of(1), this::orderConfirmed),
        "OrderCancelled", handler(Set.of(1), this::orderCancelled));
  }

  /** R2: atomic reservation. Rejecting is a success for the consumer: the outbox commits. */
  void orderCreated(Envelope e, Connection c) throws SQLException {
    OrderCreatedV2 o = Upcaster.orderCreated(e.version(), e.payload());
    if (reservations.existsByOrderId(c, o.orderId())) {
      throw new StaleEventException("order " + o.orderId() + " already has a reservation");
    }
    if (o.quantity() < 1) {
      reject(e, c, o, "INVALID_QUANTITY");
      return;
    }
    Optional<Price> price = reservations.decrement(c, o.sectorId(), o.eventId(), o.quantity());
    if (price.isEmpty()) {
      reject(
          e,
          c,
          o,
          reservations.sectorExists(c, o.sectorId(), o.eventId())
              ? "OUT_OF_STOCK"
              : "SECTOR_NOT_FOUND");
      return;
    }
    UUID reservationId = UUID.randomUUID();
    long total = Math.multiplyExact(price.get().unitPriceCents(), (long) o.quantity());
    Instant expiresAt =
        reservations.insert(
            c,
            reservationId,
            o.orderId(),
            o.sectorId(),
            o.quantity(),
            price.get().unitPriceCents(),
            total,
            reservationTtl.toMillis(),
            o.channel());
    events.reserved(
        c,
        e.correlationId(),
        reservationId,
        o.orderId(),
        o.eventId(),
        o.sectorId(),
        o.quantity(),
        price.get().unitPriceCents(),
        total,
        price.get().currency(),
        expiresAt);
    log.atInfo()
        .addKeyValue("orderId", o.orderId().toString())
        .addKeyValue("quantity", o.quantity())
        .addKeyValue("channel", o.channel())
        .log("stock_reserved");
  }

  private void reject(Envelope e, Connection c, OrderCreatedV2 o, String reason)
      throws SQLException {
    events.rejected(
        c, e.correlationId(), o.orderId(), o.eventId(), o.sectorId(), o.quantity(), reason);
    log.atInfo()
        .addKeyValue("orderId", o.orderId().toString())
        .addKeyValue("reason", reason)
        .log("stock_rejected");
  }

  /** Confirms the reservation and issues one ticket per unit (RFC 0006 §5.5). */
  void orderConfirmed(Envelope e, Connection c) throws SQLException {
    JsonNode p = e.payload();
    UUID orderId = uuid(p, "orderId");
    UUID userId = uuid(p, "userId");
    Optional<Confirmed> confirmed = reservations.confirm(c, orderId);
    if (confirmed.isEmpty()) {
      Optional<String> status = reservations.statusOf(c, orderId);
      if (status.isPresent() && status.get().equals("RELEASED")) {
        // The customer paid and the stock was already returned: the TTL invariant should prevent
        // it.
        log.atError()
            .addKeyValue("orderId", orderId.toString())
            .log("order_confirmed_after_release");
      }
      throw new StaleEventException("order " + orderId + ": no PENDING reservation to confirm");
    }
    Confirmed r = confirmed.get();
    List<Map<String, String>> tickets = new ArrayList<>();
    for (int i = 0; i < r.quantity(); i++) {
      UUID ticketId = UUID.randomUUID();
      String code = TicketCodes.next();
      reservations.insertTicket(c, ticketId, r.reservationId(), code);
      Map<String, String> ticket = new LinkedHashMap<>();
      ticket.put("ticketId", ticketId.toString());
      ticket.put("code", code);
      tickets.add(ticket);
    }
    events.ticketsIssued(c, e.correlationId(), orderId, userId, r, tickets);
    log.atInfo()
        .addKeyValue("orderId", orderId.toString())
        .addKeyValue("tickets", r.quantity())
        .log("tickets_issued");
  }

  /** Compensation (F1): only PENDING → RELEASED returns the stock. */
  void orderCancelled(Envelope e, Connection c) throws SQLException {
    UUID orderId = uuid(e.payload(), "orderId");
    if (!reservations.release(c, orderId)) {
      throw new StaleEventException("order " + orderId + ": nothing to release");
    }
    log.atInfo().addKeyValue("orderId", orderId.toString()).log("stock_released");
  }

  private static UUID uuid(JsonNode payload, String field) {
    JsonNode v = payload.get(field);
    if (v == null || !v.isString()) {
      throw new InvalidMessageException(field + " is required");
    }
    try {
      return UUID.fromString(v.asString());
    } catch (IllegalArgumentException ex) {
      throw new InvalidMessageException(field + " is not a UUID", ex);
    }
  }

  @FunctionalInterface
  private interface Step {
    void apply(Envelope envelope, Connection connection) throws SQLException;
  }

  private static MessageHandler handler(Set<Integer> versions, Step step) {
    return new MessageHandler() {
      @Override
      public Set<Integer> versions() {
        return versions;
      }

      @Override
      public void handle(Envelope envelope, Connection connection) throws SQLException {
        step.apply(envelope, connection);
      }
    };
  }
}
