package br.furb.ticketpro.order.modules.orders.web;

import br.furb.ticketpro.order.modules.orders.domain.OrderView;

/**
 * The order as the API shows it: an explicit DTO, never the database row (OWASP API3, RFC 0005
 * §5.2). totalCents/currency/cancelReason are null until the saga sets them.
 */
public record OrderResponse(
    String orderId,
    String status,
    String eventId,
    String sectorId,
    int quantity,
    Long totalCents,
    String currency,
    String cancelReason,
    String createdAt) {

  static OrderResponse from(OrderView o) {
    return new OrderResponse(
        o.id().toString(),
        o.status().name(),
        o.eventId().toString(),
        o.sectorId().toString(),
        o.quantity(),
        o.totalCents(),
        o.currency(),
        o.cancelReason() == null ? null : o.cancelReason().name(),
        o.createdAt().toString());
  }
}
