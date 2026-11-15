package br.furb.ticketpro.auth.platform.envelope;

/** The type/version pair has no schema or no handler. It is poison. */
public class UnsupportedVersionException extends PoisonMessageException {
  public UnsupportedVersionException(String message) {
    super(message);
  }
}
