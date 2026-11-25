package br.furb.ticketpro.inventory.platform.envelope;

/** The message violates its schema. It is poison. */
public class InvalidMessageException extends PoisonMessageException {
  public InvalidMessageException(String message) {
    super(message);
  }

  public InvalidMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}
