package br.furb.ticketpro.order.modules.orders.application;

import br.furb.ticketpro.order.modules.orders.domain.CancelReason;
import br.furb.ticketpro.order.modules.orders.domain.Transitions;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository;
import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository.Cancelled;
import br.furb.ticketpro.order.platform.envelope.Envelope;
import br.furb.ticketpro.order.platform.envelope.StaleEventException;
import br.furb.ticketpro.order.platform.messaging.MessageHandler;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * The saga (RFC 0005 §5.4–5.6): one handler per consumed event, each a conditional UPDATE in the
 * consumer's transaction (worker pool) plus the events it publishes. Zero rows → {@link
 * StaleEventException}: acknowledged without effect.
 */
public final class SagaHandlers {

  private static final Logger log = LoggerFactory.getLogger(SagaHandlers.class);

  private final OrderRepository orders;
  private final OrderEvents events;
  private final Duration paymentTimeout;

  public SagaHandlers(OrderRepository orders, OrderEvents events, Duration paymentTimeout) {
    this.orders = orders;
    this.events = events;
    this.paymentTimeout = paymentTimeout;
  }

  /** Handlers by event type, for the SqsConsumer. */
  public Map<String, MessageHandler> all() {
    return Map.of(
        "InventoryReserved", v1(this::inventoryReserved),
        "InventoryRejected", v1(this::inventoryRejected),
        "InventoryReservationExpired", v1(this::reservationExpired),
        "PaymentApproved", v1(this::paymentApproved),
        "PaymentFailed", v1(this::paymentFailed),
        "PaymentRefunded", v1(this::paymentRefunded));
  }

  void inventoryReserved(Envelope e, Connection c) throws SQLException {
    JsonNode p = e.payload();
    UUID orderId = Payloads.uuid(p, "orderId");
    var reserved =
        orders
            .toAwaitingPayment(
                c,
                orderId,
                Payloads.cents(p, "totalCents"),
                Payloads.text(p, "currency"),
                paymentTimeout.toMillis())
            .orElseThrow(() -> stale(orderId, "not PENDING"));
    events.paymentRequested(c, e.correlationId(), reserved);
  }

  void inventoryRejected(Envelope e, Connection c) throws SQLException {
    // Decision 4: every InventoryRejected reason cancels as OUT_OF_STOCK.
    cancel(e, c, Transitions.CANCEL_OUT_OF_STOCK, CancelReason.OUT_OF_STOCK);
  }

  void reservationExpired(Envelope e, Connection c) throws SQLException {
    cancel(e, c, Transitions.CANCEL_RESERVATION_EXPIRED, CancelReason.RESERVATION_EXPIRED);
  }

  void paymentFailed(Envelope e, Connection c) throws SQLException {
    cancel(e, c, Transitions.CANCEL_PAYMENT_FAILED, CancelReason.PAYMENT_FAILED);
  }

  /**
   * Confirms within the deadline; otherwise the approval is late and the payment is refunded once
   * (RFC 0005 §5.6). The race with the deadline scanner is decided by the row lock and the
   * re-evaluated WHERE of each UPDATE.
   */
  void paymentApproved(Envelope e, Connection c) throws SQLException {
    JsonNode p = e.payload();
    UUID orderId = Payloads.uuid(p, "orderId");
    UUID paymentId = Payloads.uuid(p, "paymentId");
    long amount = Payloads.cents(p, "amountCents");
    String currency = Payloads.text(p, "currency");

    var confirmed = orders.confirm(c, orderId);
    if (confirmed.isPresent()) {
      events.orderConfirmed(c, e.correlationId(), confirmed.get());
      return;
    }
    Optional<Cancelled> cancelledNow =
        orders.cancel(c, Transitions.CANCEL_LATE_AND_REQUEST_REFUND, orderId);
    if (cancelledNow.isPresent()) {
      events.orderCancelled(c, e.correlationId(), cancelledNow.get(), CancelReason.PAYMENT_TIMEOUT);
    } else if (!orders.updateRefund(c, Transitions.REQUEST_REFUND, orderId)) {
      throw stale(orderId, "already confirmed, refund already requested or not awaiting payment");
    }
    events.refundRequested(c, e.correlationId(), orderId, paymentId, amount, currency);
    log.atWarn()
        .addKeyValue("orderId", orderId.toString())
        .addKeyValue("paymentId", paymentId.toString())
        .log("late_payment_refund");
  }

  void paymentRefunded(Envelope e, Connection c) throws SQLException {
    UUID orderId = Payloads.uuid(e.payload(), "orderId");
    if (!orders.updateRefund(c, Transitions.MARK_REFUNDED, orderId)) {
      throw stale(orderId, "no refund requested");
    }
  }

  private void cancel(Envelope e, Connection c, String transition, CancelReason reason)
      throws SQLException {
    UUID orderId = Payloads.uuid(e.payload(), "orderId");
    Cancelled cancelled =
        orders
            .cancel(c, transition, orderId)
            .orElseThrow(() -> stale(orderId, "cannot cancel as " + reason));
    events.orderCancelled(c, e.correlationId(), cancelled, reason);
    log.atInfo()
        .addKeyValue("orderId", orderId.toString())
        .addKeyValue("reason", reason.name())
        .log("compensation_applied");
  }

  private static StaleEventException stale(UUID orderId, String why) {
    return new StaleEventException("order " + orderId + ": " + why);
  }

  @FunctionalInterface
  private interface Step {
    void apply(Envelope envelope, Connection connection) throws SQLException;
  }

  private static MessageHandler v1(Step step) {
    return new MessageHandler() {
      @Override
      public Set<Integer> versions() {
        return Set.of(1);
      }

      @Override
      public void handle(Envelope envelope, Connection connection) throws SQLException {
        step.apply(envelope, connection);
      }
    };
  }
}
