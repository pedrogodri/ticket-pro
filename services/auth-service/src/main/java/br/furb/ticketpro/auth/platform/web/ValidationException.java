package br.furb.ticketpro.auth.platform.web;

/** Invalid request input → 400 VALIDATION_ERROR; the message is the detail (no secrets). */
public class ValidationException extends RuntimeException {
  public ValidationException(String detail) {
    super(detail);
  }
}
