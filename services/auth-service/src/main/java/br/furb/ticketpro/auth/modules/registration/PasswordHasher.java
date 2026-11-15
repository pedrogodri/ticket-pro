package br.furb.ticketpro.auth.modules.registration;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

/** Argon2id password hashing (RFC 0002 §5.1), parameters from ARGON2_* env vars. */
public final class PasswordHasher {

  private static final int SALT_BYTES = 16;
  private static final int HASH_BYTES = 32;
  private final Argon2PasswordEncoder encoder;

  public PasswordHasher(int memoryKb, int iterations, int parallelism) {
    this.encoder =
        new Argon2PasswordEncoder(SALT_BYTES, HASH_BYTES, parallelism, memoryKb, iterations);
  }

  public String hash(String password) {
    return encoder.encode(password);
  }

  public boolean matches(String password, String hash) {
    return encoder.matches(password, hash);
  }
}
