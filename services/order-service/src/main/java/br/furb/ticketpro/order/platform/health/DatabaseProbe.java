package br.furb.ticketpro.order.platform.health;

/** Whether the database answers (readiness). */
@FunctionalInterface
public interface DatabaseProbe {
  boolean isUp();
}
