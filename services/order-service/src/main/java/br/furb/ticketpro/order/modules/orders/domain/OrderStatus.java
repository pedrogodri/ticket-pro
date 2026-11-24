package br.furb.ticketpro.order.modules.orders.domain;

/** Saga states (arquitetura §6): PENDING → AWAITING_PAYMENT → CONFIRMED | CANCELLED. */
public enum OrderStatus {
  PENDING,
  AWAITING_PAYMENT,
  CONFIRMED,
  CANCELLED
}
