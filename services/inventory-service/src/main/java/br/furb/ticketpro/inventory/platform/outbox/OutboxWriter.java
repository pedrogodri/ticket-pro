package br.furb.ticketpro.inventory.platform.outbox;

import br.furb.ticketpro.inventory.platform.envelope.Envelope;
import br.furb.ticketpro.inventory.platform.tracing.TraceContext;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.UUID;

/** Writes events in the caller's transaction (ADR-0005). */
public final class OutboxWriter {

  private OutboxWriter() {}

  /** Inserts the envelope with the traceparent of the current span (NULL if none). */
  public static void enqueue(Connection connection, String topicArn, Envelope envelope)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO outbox (message_id, topic_arn, type, version, correlation_id, traceparent, payload, occurred_at)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?)")) {
      ps.setObject(1, UUID.fromString(envelope.messageId()));
      ps.setString(2, topicArn);
      ps.setString(3, envelope.type());
      ps.setInt(4, envelope.version());
      ps.setObject(5, UUID.fromString(envelope.correlationId()));
      String traceparent = TraceContext.inject().orElse(null);
      if (traceparent == null) {
        ps.setNull(6, Types.VARCHAR);
      } else {
        ps.setString(6, traceparent);
      }
      ps.setString(7, envelope.payload().toString());
      ps.setTimestamp(8, Timestamp.from(envelope.occurredAt()));
      ps.executeUpdate();
    }
  }
}
