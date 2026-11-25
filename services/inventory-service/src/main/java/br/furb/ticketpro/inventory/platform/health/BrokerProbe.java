package br.furb.ticketpro.inventory.platform.health;

/** Broker state seen by the outbox relay (readiness: up | degraded). */
@FunctionalInterface
public interface BrokerProbe {
  boolean brokerUp();
}
