package br.furb.ticketpro.inventory.platform.messaging;

import br.furb.ticketpro.inventory.platform.envelope.Envelope;
import java.sql.Connection;
import java.util.Set;

/**
 * Applies one event inside the consumer's transaction. Return normally (success), throw {@code
 * StaleEventException}, {@code PoisonMessageException}, or anything else (transient).
 */
public interface MessageHandler {
  Set<Integer> versions();

  void handle(Envelope envelope, Connection connection) throws Exception;
}
