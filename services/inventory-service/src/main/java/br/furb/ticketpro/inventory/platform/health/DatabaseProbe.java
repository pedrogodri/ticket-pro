package br.furb.ticketpro.inventory.platform.health;

/** Whether the database answers (readiness). */
@FunctionalInterface
public interface DatabaseProbe {
  boolean isUp();
}
