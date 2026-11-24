package br.furb.ticketpro.order.modules.orders.application;

import br.furb.ticketpro.order.modules.orders.domain.CancelReason;
import br.furb.ticketpro.order.modules.orders.domain.OrderView;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository.Cancelled;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository.Confirmed;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository.Reserved;
import br.furb.ticketpro.order.platform.envelope.Envelope;
import br.furb.ticketpro.order.platform.outbox.OutboxWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the events the order-service publishes (contracts/events, topic ticketpro-orders) and
 * writes them to the outbox in the caller's transaction (ADR-0005).
 */
public final class OrderEvents {

  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private final String topicArn;
  private final int createdVersion;

  /**
   * @param createdVersion ORDER_CREATED_VERSION: 2 adds {@code channel} (RFC 0001 §5.5)
   */
  public OrderEvents(String topicArn, int createdVersion) {
    this.topicArn = topicArn;
    this.createdVersion = createdVersion;
  }

  public void orderCreated(Connection c, String correlationId, OrderView order)
      throws SQLException {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", order.id().toString());
    p.put("userId", order.userId().toString());
    p.put("eventId", order.eventId().toString());
    p.put("sectorId", order.sectorId().toString());
    p.put("quantity", order.quantity());
    if (createdVersion >= 2) {
      p.put("channel", "API"); // every order enters through the API (RFC 0005 decision 9)
    }
    write(c, correlationId, "OrderCreated", createdVersion, p);
  }

  public void paymentRequested(Connection c, String correlationId, Reserved r) throws SQLException {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", r.id().toString());
    p.put("userId", r.userId().toString());
    p.put("amountCents", r.totalCents());
    p.put("currency", r.currency());
    write(c, correlationId, "PaymentRequested", 1, p);
  }

  public void orderConfirmed(Connection c, String correlationId, Confirmed o) throws SQLException {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", o.id().toString());
    p.put("userId", o.userId().toString());
    p.put("eventId", o.eventId().toString());
    p.put("sectorId", o.sectorId().toString());
    p.put("quantity", o.quantity());
    p.put("totalCents", o.totalCents());
    p.put("currency", o.currency());
    p.put("confirmedAt", timestamp(o.confirmedAt()));
    write(c, correlationId, "OrderConfirmed", 1, p);
  }

  public void orderCancelled(Connection c, String correlationId, Cancelled o, CancelReason reason)
      throws SQLException {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", o.id().toString());
    p.put("userId", o.userId().toString());
    p.put("reason", reason.name());
    p.put("cancelledAt", timestamp(o.cancelledAt()));
    write(c, correlationId, "OrderCancelled", 1, p);
  }

  /** The payment and the amount come from the late PaymentApproved (RFC 0005 §5.6). */
  public void refundRequested(
      Connection c,
      String correlationId,
      UUID orderId,
      UUID paymentId,
      long amountCents,
      String currency)
      throws SQLException {
    Map<String, Object> p = new LinkedHashMap<>();
    p.put("orderId", orderId.toString());
    p.put("paymentId", paymentId.toString());
    p.put("amountCents", amountCents);
    p.put("currency", currency);
    p.put("reason", "LATE_APPROVAL");
    write(c, correlationId, "RefundRequested", 1, p);
  }

  private void write(
      Connection c, String correlationId, String type, int version, Map<String, Object> payload)
      throws SQLException {
    OutboxWriter.enqueue(c, topicArn, Envelope.create(correlationId, type, version, payload));
  }

  private static String timestamp(Instant instant) {
    return TIMESTAMP.format(instant);
  }
}
