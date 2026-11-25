package br.furb.ticketpro.auth.modules.keys;

import java.security.interfaces.RSAPublicKey;

/**
 * A previous public key kept in the JWKS after a rotation, so tokens it signed stay valid until
 * they expire.
 */
public record VerificationKey(String kid, RSAPublicKey publicKey) {}
