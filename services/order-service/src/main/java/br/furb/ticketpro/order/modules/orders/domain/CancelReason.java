package br.furb.ticketpro.order.modules.orders.domain;

/** orders.cancel_reason and OrderCancelled.reason (same four values). */
public enum CancelReason {
  OUT_OF_STOCK,
  PAYMENT_FAILED,
  PAYMENT_TIMEOUT,
  RESERVATION_EXPIRED
}
