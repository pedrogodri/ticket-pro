package br.furb.ticketpro.order.platform.web;

/** Missing resource, or one owned by another user (BOLA, RFC 0002 §5.6) → 404 NOT_FOUND. */
public class NotFoundException extends RuntimeException {
  public NotFoundException(String detail) {
    super(detail);
  }
}
