package br.furb.ticketpro.auth.modules.registration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RFC 0002 §5.1: Argon2id with parameters from env. */
class PasswordHasherTest {

  private final PasswordHasher hasher = new PasswordHasher(19456, 2, 1);

  @Test
  void verifiesTheRightPasswordOnly() {
    String hash = hasher.hash("correct horse battery");
    assertThat(hash).startsWith("$argon2id$").doesNotContain("correct horse battery");
    assertThat(hasher.matches("correct horse battery", hash)).isTrue();
    assertThat(hasher.matches("wrong password", hash)).isFalse();
  }
}
