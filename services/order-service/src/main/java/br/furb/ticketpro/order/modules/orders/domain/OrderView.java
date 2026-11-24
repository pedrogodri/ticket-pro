package br.furb.ticketpro.order.modules.orders.domain;

import java.time.Instant;
import java.util.UUID;

/** What the API exposes of an order. totalCents/currency arrive with InventoryReserved. */
public record OrderView(
    UUID id,
    UUID userId,
    UUID eventId,
    UUID sectorId,
    int quantity,
    OrderStatus status,
    CancelReason cancelReason,
    Long totalCents,
    String currency,
    Instant createdAt) {}
