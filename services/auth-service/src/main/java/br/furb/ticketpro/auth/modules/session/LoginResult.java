package br.furb.ticketpro.auth.modules.session;

/** Login response body (RFC 0002 §5.1). */
public record LoginResult(String accessToken, String refreshToken, long expiresIn) {}
