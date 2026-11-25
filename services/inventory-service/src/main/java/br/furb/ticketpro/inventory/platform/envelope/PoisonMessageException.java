package br.furb.ticketpro.inventory.platform.envelope;

/** A message that can never be processed; consumers send it to the DLQ (RFC 0003 §5.4). */
public class PoisonMessageException extends RuntimeException {
  public PoisonMessageException(String message) {
    super(message);
  }

  public PoisonMessageException(String message, Throwable cause) {
    super(message, cause);
  }
}
