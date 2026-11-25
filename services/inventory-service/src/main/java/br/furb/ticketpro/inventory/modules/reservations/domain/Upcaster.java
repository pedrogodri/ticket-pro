package br.furb.ticketpro.inventory.modules.reservations.domain;

import br.furb.ticketpro.inventory.platform.envelope.InvalidMessageException;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Upcasts OrderCreated v1 to v2 so there is a single processing path (RFC 0001 §5.5, RFC 0006
 * §5.4). v1 has no {@code channel}: every order that predates it came through the API.
 */
public final class Upcaster {

  public static final String DEFAULT_CHANNEL = "API";

  private Upcaster() {}

  public static OrderCreatedV2 orderCreated(int version, JsonNode payload) {
    String channel =
        switch (version) {
          case 1 -> DEFAULT_CHANNEL;
          case 2 -> text(payload, "channel");
          default -> throw new InvalidMessageException("unsupported OrderCreated v" + version);
        };
    JsonNode quantity = payload.get("quantity");
    if (quantity == null || !quantity.isIntegralNumber() || !quantity.canConvertToInt()) {
      throw new InvalidMessageException("quantity must be an integer");
    }
    return new OrderCreatedV2(
        uuid(payload, "orderId"),
        uuid(payload, "userId"),
        uuid(payload, "eventId"),
        uuid(payload, "sectorId"),
        quantity.asInt(),
        channel);
  }

  static UUID uuid(JsonNode payload, String field) {
    try {
      return UUID.fromString(text(payload, field));
    } catch (IllegalArgumentException e) {
      throw new InvalidMessageException(field + " is not a UUID", e);
    }
  }

  static String text(JsonNode payload, String field) {
    JsonNode v = payload.get(field);
    if (v == null || !v.isString() || v.asString().isBlank()) {
      throw new InvalidMessageException(field + " is required");
    }
    return v.asString();
  }
}
