package br.furb.ticketpro.auth.modules.registration;

/** The e-mail (case-insensitive) already has a credential → 409. */
public class EmailAlreadyRegisteredException extends RuntimeException {
  public EmailAlreadyRegisteredException() {
    super("email already registered");
  }
}
