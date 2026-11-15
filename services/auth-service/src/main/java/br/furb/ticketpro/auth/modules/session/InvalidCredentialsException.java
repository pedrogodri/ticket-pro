package br.furb.ticketpro.auth.modules.session;

/** Wrong password or unknown e-mail: the same 401, never revealing which (RFC 0002 §5.1). */
public class InvalidCredentialsException extends RuntimeException {
  public InvalidCredentialsException() {
    super("invalid credentials");
  }
}
