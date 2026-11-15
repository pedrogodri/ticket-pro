package br.furb.ticketpro.auth.modules.keys;

import java.util.function.Supplier;

/** The current signing key and, after a rotation, the previous public key. */
public interface SigningKeys extends Supplier<SigningKey> {
  VerificationKey previous();
}
