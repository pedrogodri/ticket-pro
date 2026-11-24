package br.furb.ticketpro.order.platform.web;

/** Missing or invalid identity (X-User-Id from the gateway) → 401 UNAUTHENTICATED. */
public class UnauthenticatedException extends RuntimeException {
  public UnauthenticatedException(String detail) {
    super(detail);
  }
}
