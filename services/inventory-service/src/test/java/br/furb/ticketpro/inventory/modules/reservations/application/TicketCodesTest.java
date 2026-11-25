package br.furb.ticketpro.inventory.modules.reservations.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TicketCodesTest {

  @Test
  void matchesTheContractAndAvoidsLookAlikes() {
    for (int i = 0; i < 1000; i++) {
      String code = TicketCodes.next();
      assertThat(code).matches("^[A-Z0-9]{4}-[A-Z0-9]{4}-[A-Z0-9]{4}$");
      assertThat(code).doesNotContain("O", "0", "I", "1");
    }
  }

  @Test
  void codesAreNotRepeated() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1000; i++) {
      seen.add(TicketCodes.next());
    }
    assertThat(seen).hasSize(1000);
  }
}
