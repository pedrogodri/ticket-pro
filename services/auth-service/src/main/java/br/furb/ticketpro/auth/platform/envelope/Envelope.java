package br.furb.ticketpro.auth.platform.envelope;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The six-field message envelope (RFC 0001 §5.1). */
public record Envelope(
    String messageId,
    String correlationId,
    String type,
    int version,
    Instant occurredAt,
    JsonNode payload) {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  /**
   * New envelope with a random UUID messageId (v4: the JDK has no stable v7, RFC 0001 decision 8)
   * and occurredAt = now truncated to milliseconds.
   */
  public static Envelope create(String correlationId, String type, int version, Object payload) {
    return new Envelope(
        UUID.randomUUID().toString(),
        correlationId,
        type,
        version,
        Instant.now().truncatedTo(ChronoUnit.MILLIS),
        JSON.valueToTree(payload));
  }
}
