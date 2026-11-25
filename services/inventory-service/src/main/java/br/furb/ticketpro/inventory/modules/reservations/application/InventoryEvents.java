package br.furb.ticketpro.inventory.modules.reservations.application;

import br.furb.ticketpro.inventory.modules.reservations.infrastructure.ReservationRepository.Confirmed;
import br.furb.ticketpro.inventory.platform.envelope.Envelope;
import br.furb.ticketpro.inventory.platform.outbox.OutboxWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the events the inventory-service publishes (contracts/events, topic ticketpro-inventory)
 * and writes them to the outbox in the caller's transaction (ADR-0005).
 */
public final class InventoryEvents {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final String topicArn;

  public InventoryEvents(String topicArn) {
    this.topicArn = topicArn;
  }

  public void reserved(
      Connection c,
      String correlationId,
      UUID reservationId,
      UUID orderId,
      UUID eventId,
      UUID sectorId,
      int quantity,
      long unitPriceCents,
      long totalCents,
      String currency,
      Instant expiresAt)
      throws SQLException {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    p.put("reservationId", reservationId.toString());
    p.put("eventId", eventId.toString());
    p.put("sectorId", sectorId.toString());
    p.put("quantity", quantity);
    p.put("unitPriceCents", unitPriceCents);
    p.put("totalCents", totalCents);
    p.put("currency", currency);
    p.put("expiresAt", TIMESTAMP.format(expiresAt));
    write(c, correlationId, "InventoryReserved", p);
  }

  public void rejected(
      Connection c,
      String correlationId,
      UUID orderId,
      UUID eventId,
      UUID sectorId,
      int quantity,
      String reason)
      throws SQLException {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    p.put("eventId", eventId.toString());
    p.put("sectorId", sectorId.toString());
    p.put("quantity", quantity);
    p.put("reason", reason);
    write(c, correlationId, "InventoryRejected", p);
  }

  /** {@code tickets} pairs each ticket id with its code, in order. */
  public void ticketsIssued(
      Connection c,
      String correlationId,
      UUID orderId,
      UUID userId,
      Confirmed confirmed,
      List<Map<String, String>> tickets)
      throws SQLException {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    p.put("userId", userId.toString());
    p.put("eventId", confirmed.eventId().toString());
    p.put("eventName", confirmed.eventName());
    p.put("eventStartsAt", TIMESTAMP.format(confirmed.eventStartsAt()));
    p.put("sectorId", confirmed.sectorId().toString());
    p.put("sectorName", confirmed.sectorName());
    p.put("tickets", new ArrayList<>(tickets));
    write(c, correlationId, "TicketsIssued", p);
  }

  private void write(Connection c, String correlationId, String type, Map<String, Object> payload)
      throws SQLException {
    OutboxWriter.enqueue(c, topicArn, Envelope.create(correlationId, type, 1, payload));
  }
}
