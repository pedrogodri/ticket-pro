package br.furb.ticketpro.auth.platform.health;

/** Whether the database answers (readiness). */
@FunctionalInterface
public interface DatabaseProbe {
  boolean isUp();
}
