package br.furb.ticketpro.order.platform.envelope;

/**
 * Thrown by a handler when the event no longer applies (late event, final state): the message is
 * recorded as processed and acknowledged without effect.
 */
public class StaleEventException extends RuntimeException {
  public StaleEventException(String message) {
    super(message);
  }
}
