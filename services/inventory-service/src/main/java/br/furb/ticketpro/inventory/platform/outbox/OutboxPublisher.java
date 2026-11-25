package br.furb.ticketpro.inventory.platform.outbox;

import br.furb.ticketpro.inventory.platform.envelope.Envelope;
import java.util.Optional;

/** Sends one envelope to a topic; traceparent is empty when the event has no trace context. */
@FunctionalInterface
public interface OutboxPublisher {
  void publish(String topicArn, Envelope envelope, Optional<String> traceparent);
}
