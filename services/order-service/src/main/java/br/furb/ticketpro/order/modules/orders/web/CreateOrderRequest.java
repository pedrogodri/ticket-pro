package br.furb.ticketpro.order.modules.orders.web;

import br.furb.ticketpro.order.modules.orders.application.CreateOrder;
import br.furb.ticketpro.order.platform.web.ValidationException;
import java.util.UUID;

/** POST /orders body. Unknown fields are rejected by Jackson (fail-on-unknown-properties). */
public record CreateOrderRequest(String eventId, String sectorId, Integer quantity) {

  /** Validates the body and the Idempotency-Key header (400 VALIDATION_ERROR on any problem). */
  CreateOrder.Command toCommand(UUID userId, String idempotencyKey, int maxQuantity) {
    UUID key = uuid(idempotencyKey, "Idempotency-Key header");
    UUID event = uuid(eventId, "eventId");
    UUID sector = uuid(sectorId, "sectorId");
    if (quantity == null || quantity < 1 || quantity > maxQuantity) {
      throw new ValidationException("quantity must be between 1 and " + maxQuantity);
    }
    return new CreateOrder.Command(userId, key, event, sector, quantity);
  }

  private static UUID uuid(String raw, String field) {
    if (raw == null || raw.isBlank()) {
      throw new ValidationException(field + " is required");
    }
    try {
      return UUID.fromString(raw.trim());
    } catch (IllegalArgumentException e) {
      throw new ValidationException(field + " must be a UUID");
    }
  }
}
