package br.furb.ticketpro.order.modules.orders.application;

import br.furb.ticketpro.order.platform.envelope.InvalidMessageException;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Minimal typed reads of an event payload. The schema already passed in the consumer; anything
 * still wrong is poison (RFC 0005 §5.4).
 */
final class Payloads {

  private Payloads() {}

  static UUID uuid(JsonNode payload, String field) {
    String raw = text(payload, field);
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException e) {
      throw new InvalidMessageException(field + " is not a UUID", e);
    }
  }

  static long cents(JsonNode payload, String field) {
    JsonNode v = payload.get(field);
    if (v == null || !v.canConvertToLong() || !v.isIntegralNumber() || v.asLong() < 0) {
      throw new InvalidMessageException(field + " must be a non-negative integer");
    }
    return v.asLong();
  }

  static String text(JsonNode payload, String field) {
    JsonNode v = payload.get(field);
    if (v == null || !v.isString() || v.asString().isBlank()) {
      throw new InvalidMessageException(field + " is required");
    }
    return v.asString();
  }
}
