package br.furb.ticketpro.auth.platform.envelope;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Envelope serialization and SQS body unwrapping (SNS notification or raw delivery, RFC 0001 §5.2).
 */
public final class EnvelopeCodec {

  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final DateTimeFormatter OCCURRED_AT =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private EnvelopeCodec() {}

  /** Exactly the six envelope keys, occurredAt as RFC 3339 UTC with milliseconds. */
  public static String serialize(Envelope e) {
    ObjectNode node = JSON.createObjectNode();
    node.put("messageId", e.messageId());
    node.put("correlationId", e.correlationId());
    node.put("type", e.type());
    node.put("version", e.version());
    node.put("occurredAt", OCCURRED_AT.format(e.occurredAt()));
    node.set("payload", e.payload());
    return JSON.writeValueAsString(node);
  }

  /** The envelope JSON carried by an SQS body. Throws {@link PoisonMessageException}. */
  public static String rawEnvelope(String body) {
    JsonNode root = parse(body);
    if (!root.isObject()) {
      throw new PoisonMessageException("body is not a JSON object");
    }
    JsonNode type = root.get("Type");
    JsonNode message = root.get("Message");
    if (type != null
        && "Notification".equals(type.asString())
        && message != null
        && message.isString()) {
      return message.asString();
    }
    return body;
  }

  /** Parses the envelope from an SQS body. Any failure throws {@link PoisonMessageException}. */
  public static Envelope unwrap(String body) {
    JsonNode e = parse(rawEnvelope(body));
    if (!e.isObject()) {
      throw new PoisonMessageException("envelope is not a JSON object");
    }
    try {
      String messageId = text(e, "messageId");
      String type = text(e, "type");
      JsonNode version = e.get("version");
      JsonNode payload = e.get("payload");
      if (messageId.isEmpty()
          || type.isEmpty()
          || version == null
          || !version.isInt()
          || version.asInt() < 1
          || payload == null) {
        throw new PoisonMessageException("envelope without messageId/type/version/payload");
      }
      return new Envelope(
          messageId,
          text(e, "correlationId"),
          type,
          version.asInt(),
          Instant.parse(text(e, "occurredAt")),
          payload);
    } catch (DateTimeParseException ex) {
      throw new PoisonMessageException("invalid occurredAt", ex);
    }
  }

  private static String text(JsonNode node, String field) {
    JsonNode v = node.get(field);
    return v == null || !v.isString() ? "" : v.asString();
  }

  private static JsonNode parse(String body) {
    try {
      return JSON.readTree(body);
    } catch (JacksonException e) {
      throw new PoisonMessageException("body is not JSON", e);
    }
  }
}
