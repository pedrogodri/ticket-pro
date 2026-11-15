package br.furb.ticketpro.auth.modules.keys;

/** A signed access token, its jti and its lifetime in seconds. */
public record IssuedToken(String value, String jti, long expiresIn) {}
