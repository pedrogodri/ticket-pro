package br.furb.ticketpro.inventory.platform.idempotency;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/** processed_messages in the caller's transaction (ADR-0006). */
public final class ProcessedMessages {

  private ProcessedMessages() {}

  /** Inserts (consumer, messageId); false means a duplicate: roll back and acknowledge. */
  public static boolean record(Connection connection, String consumer, UUID messageId)
      throws SQLException {
    try (PreparedStatement ps =
        connection.prepareStatement(
            "INSERT INTO processed_messages (consumer, message_id) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
      ps.setString(1, consumer);
      ps.setObject(2, messageId);
      return ps.executeUpdate() == 1;
    }
  }
}
