package br.furb.ticketpro.order.modules.orders.web;

import br.furb.ticketpro.order.modules.orders.infrastructure.OrderRepository.Position;
import br.furb.ticketpro.order.platform.web.ValidationException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/** Opaque GET /orders cursor: base64url of {@code createdAt|id} (RFC 0005 decision 6). */
public final class CursorCodec {

  private CursorCodec() {}

  public static String encode(Position position) {
    if (position == null) {
      return null;
    }
    String raw = position.createdAt() + "|" + position.id();
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
  }

  /** Null or blank → null (first page); anything malformed → 400. */
  public static Position decode(String cursor) {
    if (cursor == null || cursor.isBlank()) {
      return null;
    }
    try {
      String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
      int bar = raw.indexOf('|');
      if (bar < 0) {
        throw new IllegalArgumentException("no separator");
      }
      return new Position(
          Instant.parse(raw.substring(0, bar)), UUID.fromString(raw.substring(bar + 1)));
    } catch (RuntimeException e) {
      throw new ValidationException("cursor is invalid");
    }
  }
}
